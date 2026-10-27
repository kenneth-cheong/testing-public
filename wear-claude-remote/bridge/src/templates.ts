// Prompt templates the watch can send with one tap.
//
// They live in a JSON file next to devices.json so you can edit them without
// rebuilding the watch app. The file is read on every request, so changes show
// up the next time the watch opens its template list.
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname } from "node:path";

export interface PromptTemplate {
  label: string;
  text: string;
}

export const MAX_TEMPLATES = 30;
export const MAX_LABEL_CHARS = 40;
export const MAX_TEXT_CHARS = 4000;

export const DEFAULT_TEMPLATES: PromptTemplate[] = [
  { label: "Status", text: "Give me a three-line status: what you just did, what you're doing now, and what's left." },
  { label: "Carry on", text: "Carry on with the plan." },
  { label: "Run the tests", text: "Run the tests and tell me the result in one line." },
  { label: "Summarise", text: "Summarise this session in three short bullet points I can read on a watch." },
  { label: "Commit", text: "Commit the current changes with a clear message. Don't push." },
];

/**
 * Keeps entries with a non-empty string label and text, trimmed and capped.
 * Anything else in the file (comments, wrong types) is ignored rather than fatal.
 */
export function parseTemplates(raw: unknown): PromptTemplate[] {
  if (!Array.isArray(raw)) return [];
  const templates: PromptTemplate[] = [];
  for (const entry of raw) {
    if (!entry || typeof entry !== "object") continue;
    const { label, text } = entry as Record<string, unknown>;
    if (typeof label !== "string" || typeof text !== "string") continue;
    const cleanLabel = label.trim().slice(0, MAX_LABEL_CHARS);
    const cleanText = text.trim().slice(0, MAX_TEXT_CHARS);
    if (cleanLabel && cleanText) templates.push({ label: cleanLabel, text: cleanText });
    if (templates.length === MAX_TEMPLATES) break;
  }
  return templates;
}

export class TemplateStore {
  constructor(private readonly file: string) {}

  /** Writes the starter list if there is no file yet, so there is something to edit. */
  ensureFile(): void {
    if (existsSync(this.file)) return;
    mkdirSync(dirname(this.file), { recursive: true, mode: 0o700 });
    writeFileSync(this.file, JSON.stringify(DEFAULT_TEMPLATES, null, 2) + "\n");
  }

  /** Throws if the file is missing or not valid JSON, so the caller can report it. */
  list(): PromptTemplate[] {
    return parseTemplates(JSON.parse(readFileSync(this.file, "utf8")));
  }

  get path(): string {
    return this.file;
  }
}
