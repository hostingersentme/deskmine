#!/usr/bin/env bash
# Deskmine — stop the running server cleanly.
# Double-click this file in Finder, or run ./stop.command from the project folder.
# Sends SIGTERM, which Paper handles as a graceful shutdown (saves worlds,
# disables the plugin). Never uses kill -9, which would skip the save.

DIR="$(cd "$(dirname "$0")" && pwd)"
SERVER_DIR="$DIR/server"

server_pids() {
  {
    pgrep -f "paper.jar" 2>/dev/null || true
    lsof -tiTCP:25565 -sTCP:LISTEN 2>/dev/null || true
  } | sort -u
}

# Find java processes running paper.jar, then keep only the one whose working
# directory is THIS project's server dir. That makes the script project-specific
# (the server is launched with `cd server && java -jar paper.jar`, so the jar
# argument is relative and the cwd is the reliable identifier), and leaves any
# other Minecraft server on the machine untouched.
PIDS=""
for PID in $(server_pids); do
  CWD="$(lsof -a -p "$PID" -d cwd -Fn 2>/dev/null | sed -n 's/^n//p' | head -1)"
  if [ "$CWD" = "$SERVER_DIR" ]; then
    PIDS="$PIDS $PID"
  fi
done
PIDS="$(echo $PIDS | xargs)"

if [ -z "$PIDS" ]; then
  echo "No Deskmine server is running."
  exit 0
fi

for PID in $PIDS; do
  echo "Stopping Deskmine server (PID $PID)..."
  kill -TERM "$PID"
done

# Wait up to 30s for a clean exit.
for i in $(seq 1 30); do
  sleep 1
  STILL=""
  for PID in $PIDS; do
    kill -0 "$PID" 2>/dev/null && STILL="$STILL $PID"
  done
  if [ -z "$STILL" ]; then
    echo "Server stopped cleanly."
    exit 0
  fi
done

echo "Server did not stop within 30s; it may still be saving. Check the console."
exit 1
