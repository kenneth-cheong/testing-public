// Wear Claude Remote bridge.
//
// Runs next to Claude Code on your own computer and exposes a small HTTP API
// that the Wear OS app uses to list sessions, read transcripts, send prompts
// and answer tool-permission requests. Watches authenticate with per-device
// tokens they get by pairing (see devices.ts).
import { randomUUID } from "node:crypto";
import { createServer, type IncomingMessage, type ServerResponse } from "node:http";
import { homedir } from "node:os";
import { join } from "node:path";
import { createInterface } from "node:readline";
import {
  getSessionInfo,
  getSessionMessages,
  listSessions,
  query,
  type PermissionResult,
} from "@anthropic-ai/claude-agent-sdk";
import { DeviceRegistry, PairingCode, PAIRING_CODE_TTL_MS } from "./devices.js";
import { simplifyMessages, summarizeToolCall, type ChatLine } from "./transcript.js";

const HOST = process.env.BRIDGE_HOST ?? "127.0.0.1";
const PORT = Number(process.env.BRIDGE_PORT ?? 8787);
const DEFAULT_CWD = process.env.BRIDGE_DEFAULT_CWD ?? homedir();
const APPROVAL_TIMEOUT_MS = Number(process.env.BRIDGE_APPROVAL_TIMEOUT_MS ?? 5 * 60_000);
const MAX_BODY_BYTES = 64 * 1024;
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const STATE_DIR = process.env.BRIDGE_STATE_DIR ?? join(homedir(), ".config", "wear-claude-remote");

const devices = new DeviceRegistry(join(STATE_DIR, "devices.json"));
const pairing = new PairingCode();

if (process.argv.includes("--revoke-all")) {
  devices.revokeAll();
  console.log("Unpaired all watches.");
  process.exit(0);
}

function showPairingCode(): void {
  const code = pairing.issue();
  const minutes = PAIRING_CODE_TTL_MS / 60_000;
  console.log(`\nPairing code: ${code.slice(0, 3)} ${code.slice(3)}  (one use, valid ${minutes} minutes)`);
  if (process.stdin.isTTY) console.log("Press Enter for a new code.");
}

type RunStatus = "running" | "waiting_approval" | "done" | "error";

interface PendingApproval {
  id: string;
  tool: string;
  summary: string;
  resolve: (allow: boolean) => void;
}

interface Run {
  status: RunStatus;
  reply?: string;
  error?: string;
  pending?: PendingApproval;
  abort: AbortController;
}

// Latest run per session. Only one run per session may be active at a time.
const runs = new Map<string, Run>();

function isActive(run: Run | undefined): boolean {
  return run?.status === "running" || run?.status === "waiting_approval";
}

function waitForApproval(run: Run, tool: string, input: Record<string, unknown>, signal: AbortSignal): Promise<PermissionResult> {
  return new Promise((resolve) => {
    const finish = (allow: boolean, message = "Denied from watch") => {
      clearTimeout(timer);
      signal.removeEventListener("abort", onAbort);
      if (run.pending === pending) run.pending = undefined;
      if (run.status === "waiting_approval") run.status = "running";
      resolve(allow ? { behavior: "allow", updatedInput: input } : { behavior: "deny", message });
    };
    const pending: PendingApproval = {
      id: randomUUID(),
      tool,
      summary: summarizeToolCall(tool, input),
      resolve: (allow) => finish(allow),
    };
    const onAbort = () => finish(false, "Interrupted");
    const timer = setTimeout(() => finish(false, "No answer from watch in time"), APPROVAL_TIMEOUT_MS);
    signal.addEventListener("abort", onAbort, { once: true });
    run.pending = pending;
    run.status = "waiting_approval";
  });
}

function startRun(sessionId: string, prompt: string, cwd: string, resume: boolean): void {
  const run: Run = { status: "running", abort: new AbortController() };
  runs.set(sessionId, run);

  const q = query({
    prompt,
    options: {
      cwd,
      ...(resume ? { resume: sessionId } : { sessionId }),
      abortController: run.abort,
      canUseTool: (tool, input, { signal }) => waitForApproval(run, tool, input, signal),
    },
  });

  (async () => {
    for await (const msg of q) {
      if (msg.type === "result") {
        if (msg.subtype === "success" && !msg.is_error) {
          run.reply = msg.result;
          run.status = "done";
        } else {
          run.error = msg.subtype;
          run.status = "error";
        }
      }
    }
    if (isActive(run)) run.status = "done";
  })().catch((err: unknown) => {
    run.status = "error";
    run.error = run.abort.signal.aborted ? "interrupted" : String(err);
  });
}

function send(res: ServerResponse, status: number, body: unknown): void {
  res.writeHead(status, { "content-type": "application/json", "cache-control": "no-store" });
  res.end(JSON.stringify(body));
}

function authorized(req: IncomingMessage): boolean {
  const header = req.headers.authorization ?? "";
  return devices.isAuthorized(header.startsWith("Bearer ") ? header.slice(7) : "");
}

async function readJson(req: IncomingMessage): Promise<Record<string, unknown>> {
  const chunks: Buffer[] = [];
  let size = 0;
  for await (const chunk of req) {
    size += (chunk as Buffer).length;
    if (size > MAX_BODY_BYTES) throw new HttpError(413, "Body too large");
    chunks.push(chunk as Buffer);
  }
  try {
    const parsed: unknown = JSON.parse(Buffer.concat(chunks).toString("utf8") || "{}");
    if (parsed && typeof parsed === "object" && !Array.isArray(parsed)) return parsed as Record<string, unknown>;
  } catch {
    // fall through
  }
  throw new HttpError(400, "Expected a JSON object");
}

class HttpError extends Error {
  constructor(readonly status: number, message: string) {
    super(message);
  }
}

function requireText(body: Record<string, unknown>): string {
  const text = typeof body.text === "string" ? body.text.trim() : "";
  if (!text) throw new HttpError(400, "text is required");
  return text;
}

function runView(id: string) {
  const run = runs.get(id);
  return {
    status: run?.status ?? "idle",
    reply: run?.reply,
    error: run?.error,
    pending: run?.pending && { id: run.pending.id, tool: run.pending.tool, summary: run.pending.summary },
  };
}

async function route(req: IncomingMessage, res: ServerResponse): Promise<void> {
  const url = new URL(req.url ?? "/", "http://bridge");
  const parts = url.pathname.split("/").filter(Boolean);
  const method = req.method ?? "GET";

  if (method === "GET" && url.pathname === "/health") return send(res, 200, { ok: true });

  // POST /pair { code, name }  -> { token }  (the only unauthenticated write)
  if (method === "POST" && url.pathname === "/pair") {
    const body = await readJson(req);
    if (!pairing.active) return send(res, 403, { error: "No active pairing code. Press Enter in the bridge terminal." });
    if (typeof body.code !== "string" || !pairing.redeem(body.code)) return send(res, 403, { error: "Wrong pairing code" });
    // The name is shown in this terminal, so drop control characters (e.g. escape sequences).
    const name = (typeof body.name === "string" ? body.name : "").replace(/[\u0000-\u001f\u007f-\u009f]/g, "").slice(0, 80) || "watch";
    const token = devices.add(name);
    console.log(`Paired "${name}".`);
    return send(res, 200, { token });
  }

  if (!authorized(req)) return send(res, 401, { error: "Unauthorized" });

  // GET /sessions
  if (parts.length === 1 && parts[0] === "sessions" && method === "GET") {
    const limit = Math.min(Number(url.searchParams.get("limit") ?? 20) || 20, 100);
    const sessions = await listSessions({ limit, includeProgrammatic: true });
    return send(res, 200, sessions.map((s) => ({
      id: s.sessionId,
      title: s.customTitle ?? s.summary,
      cwd: s.cwd,
      lastModified: s.lastModified,
      status: runView(s.sessionId).status,
    })));
  }

  // POST /sessions  { text, cwd? }  -> start a new session
  if (parts.length === 1 && parts[0] === "sessions" && method === "POST") {
    const body = await readJson(req);
    const text = requireText(body);
    const cwd = typeof body.cwd === "string" && body.cwd ? body.cwd : DEFAULT_CWD;
    const id = randomUUID();
    startRun(id, text, cwd, false);
    return send(res, 202, { id });
  }

  if (parts[0] !== "sessions" || !parts[1]) return send(res, 404, { error: "Not found" });
  const id = parts[1];
  if (!UUID_RE.test(id)) return send(res, 400, { error: "Invalid session id" });

  // GET /sessions/:id?limit=  -> recent transcript + run state
  if (parts.length === 2 && method === "GET") {
    const limit = Math.min(Number(url.searchParams.get("limit") ?? 30) || 30, 200);
    const [info, raw] = await Promise.all([getSessionInfo(id), getSessionMessages(id)]);
    if (!info && raw.length === 0 && !runs.has(id)) return send(res, 404, { error: "Session not found" });
    const lines: ChatLine[] = simplifyMessages(raw).slice(-limit);
    return send(res, 200, { id, title: info?.customTitle ?? info?.summary ?? "New session", ...runView(id), messages: lines });
  }

  // POST /sessions/:id/messages { text }  -> resume the session with a new prompt
  if (parts.length === 3 && parts[2] === "messages" && method === "POST") {
    if (isActive(runs.get(id))) return send(res, 409, { error: "Session is busy" });
    const text = requireText(await readJson(req));
    const info = await getSessionInfo(id);
    if (!info) return send(res, 404, { error: "Session not found" });
    startRun(id, text, info.cwd ?? DEFAULT_CWD, true);
    return send(res, 202, { id });
  }

  // POST /sessions/:id/approval { approvalId, allow }
  if (parts.length === 3 && parts[2] === "approval" && method === "POST") {
    const body = await readJson(req);
    const pending = runs.get(id)?.pending;
    if (!pending || pending.id !== body.approvalId) return send(res, 409, { error: "No such pending approval" });
    pending.resolve(body.allow === true);
    return send(res, 200, { ok: true });
  }

  // POST /sessions/:id/interrupt
  if (parts.length === 3 && parts[2] === "interrupt" && method === "POST") {
    const run = runs.get(id);
    if (!isActive(run)) return send(res, 409, { error: "Session is not running" });
    run!.abort.abort();
    return send(res, 200, { ok: true });
  }

  return send(res, 404, { error: "Not found" });
}

createServer((req, res) => {
  route(req, res).catch((err: unknown) => {
    if (err instanceof HttpError) return send(res, err.status, { error: err.message });
    console.error(err);
    send(res, 500, { error: "Internal error" });
  });
}).listen(PORT, HOST, () => {
  console.log(`Wear Claude Remote bridge listening on http://${HOST}:${PORT}`);
  console.log(`${devices.count} watch(es) paired. Unpair all with: npm run revoke`);
  showPairingCode();
  if (process.stdin.isTTY) createInterface({ input: process.stdin }).on("line", showPairingCode);
});
