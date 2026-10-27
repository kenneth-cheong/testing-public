// Watch pairing and per-device tokens.
//
// The bridge never ships a shared secret. Instead it shows a short-lived,
// one-time pairing code; a watch that presents it receives its own random
// token. Only a SHA-256 hash of each token is kept on disk.
import { createHash, randomBytes, randomInt, timingSafeEqual } from "node:crypto";
import { chmodSync, existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname } from "node:path";

export const PAIRING_CODE_TTL_MS = 10 * 60_000;
export const PAIRING_MAX_ATTEMPTS = 5;

interface Device {
  name: string;
  tokenHash: string;
  pairedAt: number;
}

function hashToken(token: string): string {
  return createHash("sha256").update(token).digest("hex");
}

/** Paired devices, persisted as token hashes in a file only the current user can read. */
export class DeviceRegistry {
  private devices: Device[];

  constructor(private readonly file: string) {
    this.devices = existsSync(file) ? (JSON.parse(readFileSync(file, "utf8")) as Device[]) : [];
  }

  get count(): number {
    return this.devices.length;
  }

  isAuthorized(token: string): boolean {
    if (!token) return false;
    const hash = hashToken(token);
    return this.devices.some((d) => d.tokenHash === hash);
  }

  /** Registers a device and returns its new token. The token itself is never stored. */
  add(name: string): string {
    const token = randomBytes(32).toString("base64url");
    this.devices.push({ name: name.slice(0, 80) || "watch", tokenHash: hashToken(token), pairedAt: Date.now() });
    this.save();
    return token;
  }

  revokeAll(): void {
    this.devices = [];
    this.save();
  }

  private save(): void {
    mkdirSync(dirname(this.file), { recursive: true, mode: 0o700 });
    writeFileSync(this.file, JSON.stringify(this.devices, null, 2), { mode: 0o600 });
    chmodSync(this.file, 0o600);
  }
}

/** A one-time pairing code that expires and locks after too many wrong guesses. */
export class PairingCode {
  private code?: string;
  private expiresAt = 0;
  private attemptsLeft = 0;

  constructor(private readonly now: () => number = Date.now) {}

  /** Issues a fresh 6-digit code, replacing any previous one. */
  issue(): string {
    this.code = randomInt(0, 1_000_000).toString().padStart(6, "0");
    this.expiresAt = this.now() + PAIRING_CODE_TTL_MS;
    this.attemptsLeft = PAIRING_MAX_ATTEMPTS;
    return this.code;
  }

  get active(): boolean {
    return this.code !== undefined && this.now() < this.expiresAt && this.attemptsLeft > 0;
  }

  /** Returns true exactly once for the right code; every attempt counts against the limit. */
  redeem(guess: string): boolean {
    if (!this.active || !this.code) return false;
    this.attemptsLeft -= 1;
    const digits = guess.replace(/\D/g, "");
    const ok = digits.length === this.code.length && timingSafeEqual(Buffer.from(digits), Buffer.from(this.code));
    if (ok) this.code = undefined;
    return ok;
  }
}
