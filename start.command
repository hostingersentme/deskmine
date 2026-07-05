#!/usr/bin/env bash
# Deskmine — start the server.
# Double-click this file in Finder, or run ./start.command from the project folder.
# It hands off to server-setup.sh (rechecks Java, rebuilds/installs the plugin,
# then runs Paper in this window). The server console stays in this Terminal
# window — you can type `stop` here, or double-click stop.command instead.

DIR="$(cd "$(dirname "$0")" && pwd)"
SERVER_DIR="$DIR/server"

server_pids() {
  {
    pgrep -f "paper.jar" 2>/dev/null || true
    lsof -tiTCP:25565 -sTCP:LISTEN 2>/dev/null || true
  } | sort -u
}

# Guard: don't launch a second server if one from this project is already up
# (it would fail on the port anyway). Identify it by working directory.
for PID in $(server_pids); do
  CWD="$(lsof -a -p "$PID" -d cwd -Fn 2>/dev/null | sed -n 's/^n//p' | head -1)"
  if [ "$CWD" = "$SERVER_DIR" ]; then
    LISTENING="$(lsof -nP -a -p "$PID" -iTCP:25565 -sTCP:LISTEN 2>/dev/null | awk 'NR==2 {print $9}')"
    echo "Deskmine is already running (PID $PID) at ${LISTENING:-port 25565}."
    echo "Join with a Minecraft 26.1.2 client, or double-click stop.command to restart it."
    echo
    read -r -p "Press Return to close this window."
    exit 0
  fi
done

exec "$DIR/server-setup.sh"
