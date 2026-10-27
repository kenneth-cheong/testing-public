import assert from "node:assert/strict";
import { test } from "node:test";
import type { SessionMessage } from "@anthropic-ai/claude-agent-sdk";
import { simplifyMessages, summarizeToolCall } from "./transcript.js";

function entry(type: SessionMessage["type"], message: unknown, parent: string | null = null): SessionMessage {
  return { type, uuid: "u", session_id: "s", message, parent_tool_use_id: parent, parent_agent_id: null };
}

test("summarizeToolCall prefers the most descriptive input field", () => {
  assert.equal(summarizeToolCall("Bash", { command: "npm test", description: "Run tests" }), "Bash: npm test");
  assert.equal(summarizeToolCall("Read", { file_path: "/a/b.ts" }), "Read: /a/b.ts");
  assert.equal(summarizeToolCall("TodoWrite", { todos: [] }), "TodoWrite");
  assert.equal(summarizeToolCall("Bash", { command: "x".repeat(500) }).length, 160);
});

test("simplifyMessages keeps text and tool calls, drops tool results and subagents", () => {
  const lines = simplifyMessages([
    entry("user", { role: "user", content: "Fix the build" }),
    entry("assistant", { content: [{ type: "thinking", thinking: "" }, { type: "text", text: "On it." }, { type: "tool_use", name: "Bash", input: { command: "npm run build" } }] }),
    entry("user", { content: [{ type: "tool_result", tool_use_id: "t", content: "ok" }] }),
    entry("assistant", { content: [{ type: "text", text: "subagent noise" }] }, "toolu_1"),
    entry("system", { content: "compact" }),
    entry("assistant", { content: [{ type: "text", text: "Build is green." }] }),
  ]);
  assert.deepEqual(lines, [
    { role: "user", text: "Fix the build" },
    { role: "assistant", text: "On it." },
    { role: "tool", text: "Bash: npm run build" },
    { role: "assistant", text: "Build is green." },
  ]);
});
