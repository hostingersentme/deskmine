#!/usr/bin/env bash
# Deskmine - open to this local LAN, no whitelist.
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

lan_ip() {
  ipconfig getifaddr en0 2>/dev/null \
    || ipconfig getifaddr en1 2>/dev/null \
    || echo "your Mac's LAN IP"
}

listener() {
  lsof -nP -iTCP:25565 -sTCP:LISTEN 2>/dev/null | awk 'NR==2 {print $1 " PID " $2 " on " $9}'
}

if [ ! -f "$PROPS" ]; then
  echo "Could not find $PROPS"
  read -r -p "Press Return to close this window."
  exit 1
fi

set_prop "server-ip" ""
set_prop "online-mode" "true"
set_prop "enforce-secure-profile" "true"
set_prop "white-list" "false"
set_prop "enforce-whitelist" "false"

IP="$(lan_ip)"
echo "Deskmine LAN mode is ON."
echo "Whitelist is OFF. Anyone on this LAN with a valid Minecraft account can join."
echo
echo "Join from another device on this network with:"
echo "  $IP:25565"
echo
echo "Do not enable router port forwarding unless you mean to publish this server."
RUNNING="$(listener)"
if [ -n "$RUNNING" ]; then
  echo
  echo "Restart required: Paper is already running as $RUNNING."
  echo "Stop it with stop.command, then start it again with start.command."
else
  echo "Start the server with start.command."
fi
echo
read -r -p "Press Return to close this window."
