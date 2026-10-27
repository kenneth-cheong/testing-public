// The name the watch shows for this computer, e.g. "MacBook Air".
import { execFileSync } from "node:child_process";
import { hostname } from "node:os";

/**
 * Shortens a computer name for a small screen: drops an owner prefix like
 * "Kenneth's " and a trailing ".local", and turns host-style dashes into spaces.
 */
export function machineLabel(name: string): string {
  const cleaned = name
    .trim()
    .replace(/\.local$/i, "")
    .replace(/^.+?['’]s[\s-]+/i, "")
    // macOS host names lose the apostrophe: "Kenneths-MacBook-Pro".
    .replace(/^[^\s-]+s-(?=(MacBook|iMac|Mac)\b)/i, "");
  const spaced = /\s/.test(cleaned) ? cleaned : cleaned.replace(/-/g, " ");
  return spaced.slice(0, 40) || "Computer";
}

/** BRIDGE_MACHINE_NAME if set, else the macOS computer name, else the host name. */
export function computerName(env: NodeJS.ProcessEnv = process.env): string {
  if (env.BRIDGE_MACHINE_NAME?.trim()) return env.BRIDGE_MACHINE_NAME.trim().slice(0, 40);
  if (process.platform === "darwin") {
    try {
      return machineLabel(execFileSync("scutil", ["--get", "ComputerName"], { encoding: "utf8" }));
    } catch {
      // fall back to the host name
    }
  }
  return machineLabel(hostname());
}
