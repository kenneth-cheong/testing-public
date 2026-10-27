import assert from "node:assert/strict";
import { test } from "node:test";
import { computerName, machineLabel } from "./machine.js";

test("machineLabel drops the owner prefix and host-name noise", () => {
  assert.equal(machineLabel("Kenneth’s MacBook Air"), "MacBook Air");
  assert.equal(machineLabel("Kenneth's MacBook Pro\n"), "MacBook Pro");
  assert.equal(machineLabel("Kenneths-MacBook-Pro.local"), "MacBook Pro");
  assert.equal(machineLabel("build-server"), "build server");
  assert.equal(machineLabel("Studio"), "Studio");
  assert.equal(machineLabel("  "), "Computer");
});

test("computerName prefers the override", () => {
  assert.equal(computerName({ BRIDGE_MACHINE_NAME: " Work Mac " }), "Work Mac");
  assert.ok(computerName({}).length > 0);
});
