# Claude Remote for Wear OS

Read and drive your Claude Code sessions from your watch: see what Claude is
doing, reply by voice, start new tasks, and allow or deny tool calls (with a
buzz on your wrist when Claude needs a decision).

```
 Wear OS watch ──HTTPS──▶ bridge (Node, on your computer) ──Agent SDK──▶ Claude Code sessions
  Compose app             REST API, per-watch tokens            ~/.claude/projects/*.jsonl
```

There is no public API for claude.ai chats or claude.ai/code cloud sessions,
so this works with the **Claude Code sessions on the computer running the
bridge**. That includes sessions you started in a terminal: they show up in the
list and you can continue them from the watch. Run a bridge on each computer
you use (say a MacBook Air and a MacBook Pro) and pair the watch with all of
them to get one list labelled by computer.

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
npm start                                     # listens on 127.0.0.1:8787
```

On start the bridge prints a **pairing code** like `Pairing code: 482 913`.
The code works once, expires after 10 minutes, and locks after 5 wrong tries.
Press Enter in the bridge terminal to get a new one.

Optional settings (environment variables):

| Variable | Default | Meaning |
|---|---|---|
| `BRIDGE_PORT` | `8787` | Port to listen on |
| `BRIDGE_HOST` | `127.0.0.1` | Interface to bind; keep it local and use a tunnel (below) |
| `BRIDGE_DEFAULT_CWD` | your home directory | Working directory for sessions started from the watch |
| `BRIDGE_APPROVAL_TIMEOUT_MS` | `300000` | How long a tool call waits for Allow/Deny before it is denied |
| `BRIDGE_STATE_DIR` | `~/.config/wear-claude-remote` | Where paired watches are stored |
| `BRIDGE_TEMPLATES_FILE` | `templates.json` in the state directory | Prompt templates the watch can send with one tap |
| `BRIDGE_MACHINE_NAME` | the computer name, shortened (`Kenneth's MacBook Air` → `MacBook Air`) | Label the watch shows for this computer's sessions |

**How the token is protected.** No secret is built into the app.
- **Pairing:** pairing gives each watch its own random 256-bit token.
- **On the watch:** the token is encrypted with an Android Keystore key that never leaves the watch's hardware.
- **On the bridge:** only a SHA-256 hash of each token is stored, in `devices.json` (permissions `0600`).
- **Revoking:** `npm run revoke` unpairs every watch. A revoked watch goes back to its pairing screen automatically.

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

Funnel makes the URL public. Every request except pairing must carry a paired
watch's token, and pairing needs the one-time code from your terminal. If your watch can reach your tailnet privately,
use `tailscale serve --bg 8787` instead. A Cloudflare named tunnel works too.

## 3. Install the watch app

**Get the APK** in one of two ways:
- **Download it.** GitHub Actions builds it on every change. Open the latest
  **Wear Claude Remote** run in the repo's Actions tab and download the
  `claude-remote-debug-apk` artifact. The APK holds no secrets.
- **Build it yourself.** Open `wear-claude-remote/wear` in Android Studio, or
  run `./gradlew :app:assembleDebug`. To pre-fill the bridge address, copy
  `local.properties.example` to `local.properties` first.

**Install it on the watch:**
1. On the watch, turn on Developer options → ADB debugging and Wireless debugging.
2. Connect from your computer: `adb pair <ip>:<pair-port>`, then `adb connect <ip>:<port>`.
3. Run `adb install app-debug.apk`. If a build from another machine is already
   installed, run `adb uninstall io.github.kennethcheong.clauderemote` first,
   because debug builds are signed with per-machine keys.

**Pair it:**
1. Open **Claude Remote** on the watch.
2. Set **Bridge address**. A bare host name like `my-pc.tailnet.ts.net` is enough.
3. Tap **Enter pairing code** and type or say the code from the bridge terminal.

**Add another computer:** start a bridge there (and a tunnel, step 2), then on
the watch scroll to the bottom of the session list, tap **Add computer** and
pair the same way. Each computer gets its own token.

## Using it

- **Session list**: newest first, with the project folder and the last activity.
  The **New** button starts a session from voice, keyboard or handwriting.
  With more than one computer paired, sessions from all of them share the list,
  each labelled with its computer (`MacBook Pro · api · 5 min ago`), and **New**
  first asks which computer to run on. A computer that can't be reached shows a
  one-line error and keeps its last known sessions.
- **Thread**: the last 30 lines of the conversation. Tool calls appear as `⚙ Bash: npm test`.
  Use **Reply** to continue the session, or **Stop** to interrupt Claude mid-run.
- **Templates**: canned prompts you send with one tap. **New from template**
  (top of the session list) starts a session with one; **Templates** (under
  a thread) sends one as the next reply. They live in
  `~/.config/wear-claude-remote/templates.json` on your computer, a list of
  `{ "label": "Status", "text": "What's left?" }` entries. The bridge writes a
  starter list on first run and re-reads the file every time the watch opens
  the list, so edits need no restart or rebuild.
- **Unpair MacBook Pro** and the like (one per computer, at the bottom of the list) forget the watch's token for that computer.
- **Approvals**: when Claude wants to use a tool that your Claude Code
  permission settings don't already allow, the watch buzzes and shows
  **Allow / Deny**. If nobody answers within 5 minutes, the request is denied.

The app polls every 2.5 seconds while Claude is working and every 15 seconds
otherwise, and only while the screen is visible.

## Bridge API

All endpoints except `/health` and `/pair` need `Authorization: Bearer <device token>`.

| Method & path | Body | Result |
|---|---|---|
| `GET /health` | | `{ ok: true }` |
| `POST /pair` | `{ code, name }` | `{ token, machine }` for a correct, unexpired pairing code |
| `GET /info` | | `{ machine }`, the label for this computer |
| `GET /templates` | | `[{ label, text }]` from the templates file |
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

## Development

```sh
cd bridge && npm test                      # transcript formatting and pairing tests
cd wear && ./gradlew :app:testDebugUnitTest  # BridgeClient tests (MockWebServer)
```
