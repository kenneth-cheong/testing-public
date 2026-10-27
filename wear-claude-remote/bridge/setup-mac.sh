#!/bin/zsh
# Sets up the Claude Remote bridge on another Mac and opens a Tailscale Funnel to it.
# Run from anywhere:  zsh setup-mac.sh   (needs Homebrew, Tailscale and ANTHROPIC_API_KEY)
set -euo pipefail

REPO_URL=https://github.com/kenneth-cheong/testing-public.git
BRANCH=claude/wearos-claude-remote-app-d60hah
DIR="$HOME/testing-public"

: "${ANTHROPIC_API_KEY:?Set ANTHROPIC_API_KEY first: export ANTHROPIC_API_KEY=sk-ant-...}"

command -v node >/dev/null || brew install node
if [[ -d "$DIR/.git" ]]; then
  git -C "$DIR" fetch origin "$BRANCH"
  git -C "$DIR" checkout "$BRANCH"
  git -C "$DIR" pull --ff-only origin "$BRANCH"
else
  git clone --branch "$BRANCH" "$REPO_URL" "$DIR"
fi

cd "$DIR/wear-claude-remote/bridge"
npm ci
npm run build

TS=$(command -v tailscale || echo /Applications/Tailscale.app/Contents/MacOS/Tailscale)
"$TS" funnel --bg 8787
echo "Watch address: https://$("$TS" status --json | python3 -c 'import json,sys; print(json.load(sys.stdin)["Self"]["DNSName"].rstrip("."))')"

# Stays in the foreground and prints the pairing code; press Enter for a new one.
npm start
