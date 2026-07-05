#!/usr/bin/env bash
# Deskmine - lock back to this Mac only.
# Double-click in Finder, then restart the server if it is already running.
set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
PROPS="$DIR/server/server.properties"

set_prop() {
  local key="$1"
  local value="$2"
  if grep -q "^${key}=" "$PROPS" 2>/dev/null; then
    sed -i.bak "s|^${key}=.*|${key}=${value}|" "$PROPS"
    rm -f "$PROPS.bak"
  else
    printf '%s=%s\n' "$key" "$value" >> "$PROPS"
  fi
}

listener() {
  lsof -nP -iTCP:25565 -sTCP:LISTEN 2>/dev/null | awk 'NR==2 {print $1 " PID " $2 " on " $9}'
}

if [ ! -f "$PROPS" ]; then
  echo "Could not find $PROPS"
  read -r -p "Press Return to close this window."
  exit 1
fi

set_prop "server-ip" "127.0.0.1"
set_prop "online-mode" "false"
set_prop "enforce-secure-profile" "false"
set_prop "white-list" "false"
set_prop "enforce-whitelist" "false"

echo "Deskmine LAN mode is OFF."
echo "The server is locked to this Mac only."
echo
echo "Join locally with:"
echo "  localhost"
echo
RUNNING="$(listener)"
if [ -n "$RUNNING" ]; then
  echo "Restart required: Paper is already running as $RUNNING."
  echo "Stop it with stop.command, then start it again with start.command."
else
  echo "Start the server with start.command."
fi
echo
read -r -p "Press Return to close this window."
