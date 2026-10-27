// Turns Claude Code transcript entries into short lines a watch can show.
import type { SessionMessage } from "@anthropic-ai/claude-agent-sdk";

export interface ChatLine {
  role: "user" | "assistant" | "tool";
  text: string;
}

const MAX_LINE_CHARS = 1500;
const MAX_SUMMARY_CHARS = 160;

function clip(text: string, max: number): string {
  const trimmed = text.trim();
  return trimmed.length > max ? `${trimmed.slice(0, max - 1)}…` : trimmed;
}

/** One-line description of a tool call, e.g. `Bash: npm test`. */
export function summarizeToolCall(tool: string, input: Record<string, unknown>): string {
  const detail = ["command", "file_path", "path", "pattern", "url", "query", "description"]
    .map((key) => input[key])
    .find((value): value is string => typeof value === "string" && value.length > 0);
  return clip(detail ? `${tool}: ${detail}` : tool, MAX_SUMMARY_CHARS);
}

interface Block {
  type?: unknown;
  text?: unknown;
  name?: unknown;
  input?: unknown;
}

function blocksOf(message: unknown): Block[] | string | undefined {
  if (!message || typeof message !== "object") return undefined;
  const content = (message as { content?: unknown }).content;
  if (typeof content === "string") return content;
  return Array.isArray(content) ? (content as Block[]) : undefined;
}

export function simplifyMessages(messages: SessionMessage[]): ChatLine[] {
  const lines: ChatLine[] = [];
  for (const entry of messages) {
    // Skip subagent chatter; the watch only shows the main conversation.
    if (entry.parent_tool_use_id) continue;
    if (entry.type !== "user" && entry.type !== "assistant") continue;

    const blocks = blocksOf(entry.message);
    if (typeof blocks === "string") {
      if (blocks.trim()) lines.push({ role: entry.type, text: clip(blocks, MAX_LINE_CHARS) });
      continue;
    }
    for (const block of blocks ?? []) {
      if (block.type === "text" && typeof block.text === "string" && block.text.trim()) {
        lines.push({ role: entry.type, text: clip(block.text, MAX_LINE_CHARS) });
      } else if (entry.type === "assistant" && block.type === "tool_use" && typeof block.name === "string") {
        const input = block.input && typeof block.input === "object" ? (block.input as Record<string, unknown>) : {};
        lines.push({ role: "tool", text: summarizeToolCall(block.name, input) });
      }
    }
  }
  return lines;
}
