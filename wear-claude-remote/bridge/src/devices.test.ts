import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, statSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";
import { DeviceRegistry, PAIRING_CODE_TTL_MS, PAIRING_MAX_ATTEMPTS, PairingCode } from "./devices.js";

test("pairing code is single-use and accepts spoken-style spacing", () => {
  const pairing = new PairingCode();
  const code = pairing.issue();
  assert.match(code, /^\d{6}$/);
  assert.equal(pairing.redeem(`${code.slice(0, 3)} ${code.slice(3)}`), true);
  assert.equal(pairing.redeem(code), false);
});

test("pairing code expires", () => {
  let now = 1_000;
  const pairing = new PairingCode(() => now);
  const code = pairing.issue();
  now += PAIRING_CODE_TTL_MS;
  assert.equal(pairing.redeem(code), false);
});

test("pairing code locks after too many wrong guesses", () => {
  const pairing = new PairingCode();
  const code = pairing.issue();
  const wrong = code === "000000" ? "111111" : "000000";
  for (let i = 0; i < PAIRING_MAX_ATTEMPTS; i++) assert.equal(pairing.redeem(wrong), false);
  assert.equal(pairing.redeem(code), false);
});

test("registry stores only token hashes, privately, and can revoke", () => {
  const file = join(mkdtempSync(join(tmpdir(), "wcr-")), "nested", "devices.json");
  const registry = new DeviceRegistry(file);
  const token = registry.add("Pixel Watch");

  assert.equal(registry.isAuthorized(token), true);
  assert.equal(registry.isAuthorized("nope"), false);
  assert.equal(registry.isAuthorized(""), false);
  assert.equal(readFileSync(file, "utf8").includes(token), false);
  assert.equal(statSync(file).mode & 0o777, 0o600);

  assert.equal(new DeviceRegistry(file).isAuthorized(token), true, "survives a restart");
  registry.revokeAll();
  assert.equal(new DeviceRegistry(file).isAuthorized(token), false);
});
