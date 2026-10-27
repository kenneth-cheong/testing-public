import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";
import { DEFAULT_TEMPLATES, MAX_LABEL_CHARS, MAX_TEMPLATES, TemplateStore, parseTemplates } from "./templates.js";

test("parseTemplates keeps valid entries, trimmed, and skips the rest", () => {
  const parsed = parseTemplates([
    { label: "  Status ", text: " What's left? " },
    { label: "", text: "no label" },
    { label: "No text", text: "   " },
    { label: 3, text: "wrong type" },
    "not an object",
    null,
  ]);
  assert.deepEqual(parsed, [{ label: "Status", text: "What's left?" }]);
  assert.deepEqual(parseTemplates({ label: "x", text: "y" }), [], "a bare object is not a list");
});

test("parseTemplates caps label length and list size", () => {
  const many = Array.from({ length: MAX_TEMPLATES + 5 }, (_, i) => ({ label: "x".repeat(100), text: `t${i}` }));
  const parsed = parseTemplates(many);
  assert.equal(parsed.length, MAX_TEMPLATES);
  assert.equal(parsed[0].label.length, MAX_LABEL_CHARS);
});

test("store writes the starter list once and reads edits without a restart", () => {
  const file = join(mkdtempSync(join(tmpdir(), "wcr-")), "nested", "templates.json");
  const store = new TemplateStore(file);
  store.ensureFile();
  assert.deepEqual(store.list(), DEFAULT_TEMPLATES);

  writeFileSync(file, JSON.stringify([{ label: "Ping", text: "Say pong." }]));
  store.ensureFile();
  assert.deepEqual(JSON.parse(readFileSync(file, "utf8")), [{ label: "Ping", text: "Say pong." }], "existing file is kept");
  assert.deepEqual(store.list(), [{ label: "Ping", text: "Say pong." }]);

  writeFileSync(file, "{ not json");
  assert.throws(() => store.list());
});
