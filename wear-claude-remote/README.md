# Claude Remote for Wear OS

Read and drive your Claude Code sessions from your watch: see what Claude is
doing, reply by voice, start new tasks, and allow or deny tool calls (with a
buzz on your wrist when Claude needs a decision).

```
 Wear OS watch ──HTTPS──▶ bridge (Node, on your computer) ──Agent SDK──▶ Claude Code sessions
  Compose app             token-protected REST API              ~/.claude/projects/*.jsonl
```

There is no public API for claude.ai chats or claude.ai/code cloud sessions,
so this works with the **Claude Code sessions on the computer running the
bridge**. That includes sessions you started in a terminal: they show up in the
list and you can continue them from the watch.

## What's here

| Path | What it is |
|---|---|
| `bridge/` | TypeScript HTTP server that uses `@anthropic-ai/claude-agent-sdk` (`listSessions`, `getSessionMessages`, `query({ resume })`, `canUseTool`). |
| `wear/` | Wear OS app (Kotlin, Compose for Wear OS Material 3). |

## 1. Run the bridge

Needs Node 20+ on the computer where you use Claude Code.

```sh
cd wear-claude-remote/bridge
npm install
npm run build
export BRIDGE_TOKEN=$(openssl rand -hex 24)   # keep this; the watch needs it
npm start                                     # listens on 127.0.0.1:8787
```

Optional settings (environment variables):

| Variable | Default | Meaning |
|---|---|---|
| `BRIDGE_PORT` | `8787` | Port to listen on |
| `BRIDGE_HOST` | `127.0.0.1` | Interface to bind; keep it local and use a tunnel (below) |
| `BRIDGE_DEFAULT_CWD` | your home directory | Working directory for sessions started from the watch |
| `BRIDGE_APPROVAL_TIMEOUT_MS` | `300000` | How long a tool call waits for Allow/Deny before it is denied |

**Authentication.** The bridge runs Claude Code through the Agent SDK, which
documents API-key authentication: set `ANTHROPIC_API_KEY` before `npm start`.
Anthropic's docs say third-party products may not offer claude.ai login; they
don't spell out a personal tool like this one using your own login, so the API
key is the documented path.

## 2. Make the bridge reachable from the watch

The watch app only talks HTTPS. The simplest stable option is
[Tailscale Funnel](https://tailscale.com/kb/1223/funnel):

```sh
tailscale funnel --bg 8787
# -> https://<your-computer>.<your-tailnet>.ts.net
```

Funnel makes the URL public, so every request must carry the bridge token, and
the token is 192 random bits. If your watch can reach your tailnet privately,
use `tailscale serve --bg 8787` instead. A Cloudflare named tunnel works too.

## 3. Build and install the watch app

1. Open `wear-claude-remote/wear` in Android Studio.
2. Copy `local.properties.example` to `local.properties` (it's git-ignored) and
   set `bridge.url` and `bridge.token`.
3. On the watch, turn on Developer options → ADB debugging and Wireless
   debugging, then pair: `adb pair <ip>:<pair-port>` and `adb connect <ip>:<port>`.
4. Run the `app` configuration on the watch.

The token is compiled into the APK, which is fine for a personal sideloaded app.
Don't publish the build.

## Using it

- **Session list**: newest first, with the project folder and the last activity.
  The **New** button starts a session from voice, keyboard or handwriting.
- **Thread**: the last 30 lines of the conversation. Tool calls appear as `⚙ Bash: npm test`.
  Use **Reply** to continue the session, or **Stop** to interrupt Claude mid-run.
- **Approvals**: when Claude wants to use a tool that your Claude Code
  permission settings don't already allow, the watch buzzes and shows
  **Allow / Deny**. If nobody answers within 5 minutes, the request is denied.

The app polls every 2.5 seconds while Claude is working and every 15 seconds
otherwise, and only while the screen is visible.

## Bridge API

All endpoints except `/health` need `Authorization: Bearer $BRIDGE_TOKEN`.

| Method & path | Body | Result |
|---|---|---|
| `GET /health` | | `{ ok: true }` |
| `GET /sessions?limit=20` | | `[{ id, title, cwd, lastModified, status }]` |
| `POST /sessions` | `{ text, cwd? }` | `202 { id }`, starts a new session |
| `GET /sessions/:id?limit=30` | | `{ id, title, status, reply?, error?, pending?, messages: [{ role, text }] }` |
| `POST /sessions/:id/messages` | `{ text }` | `202`, resumes the session with a prompt (`409` if busy) |
| `POST /sessions/:id/approval` | `{ approvalId, allow }` | Answers a pending tool request |
| `POST /sessions/:id/interrupt` | | Stops the running turn |

`status` is one of `idle`, `running`, `waiting_approval`, `done` or `error`.

## Limitations and next steps

- `status` only reflects runs started through the bridge. A session busy in
  your terminal shows as `idle`. Don't continue it from the watch at the same
  time, because both processes would write to the same transcript.
- Answers arrive only while the app is open. A push notification when a run
  finishes or needs approval (for example via ntfy or FCM) would be the next
  thing to add.
- Entering the bridge URL and token on the watch itself (or pairing through a
  phone app) would remove the need to rebuild when they change.

## Development

```sh
cd bridge && npm test                      # transcript formatting tests
cd wear && ./gradlew :app:testDebugUnitTest  # BridgeClient tests (MockWebServer)
```
