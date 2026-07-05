#!/usr/bin/env bash
# Deskmine — reset the world.
# Wipes the generated Deskmine world so it regenerates with fresh terrain,
# removes the old unused void dimension left over from earlier versions, keeps
# your path index (room<->coordinate map), then relaunches the server.
# Double-click this file in Finder, or run ./reset-world.command.

set -u
DIR="$(cd "$(dirname "$0")" && pwd)"
SERVER_DIR="$DIR/server"
DIM_DIR="$SERVER_DIR/world/dimensions/minecraft"
INDEX="$SERVER_DIR/plugins/Deskmine/index.tsv"
CFG="$SERVER_DIR/plugins/Deskmine/config.yml"

# Resolve the live world name from config, falling back to the plugin default.
WORLD_NAME="deskmine-hills"
if [ -f "$CFG" ]; then
  N="$(awk '
    /^world:/ {inblock=1; next}
    inblock && /^[^[:space:]]/ {inblock=0}
    inblock && /name:/ {
      sub(/.*name:[[:space:]]*/, ""); gsub(/["'\'']/, ""); print; exit
    }' "$CFG")"
  [ -n "${N:-}" ] && WORLD_NAME="$N"
fi

echo "Deskmine — reset world '$WORLD_NAME'"
echo

# 1) Stop this project's server if it is running. Identify it by working
#    directory so other Minecraft servers on the machine are left alone.
for PID in $(pgrep -f "paper.jar" 2>/dev/null || true); do
  CWD="$(lsof -a -p "$PID" -d cwd -Fn 2>/dev/null | sed -n 's/^n//p' | head -1)"
  if [ "$CWD" = "$SERVER_DIR" ]; then
    echo "Stopping running server (PID $PID)..."
    kill -TERM "$PID"
    for i in $(seq 1 30); do
      sleep 1
      kill -0 "$PID" 2>/dev/null || { echo "  stopped cleanly."; break; }
    done
  fi
done

# The path index lives outside the world and is deliberately preserved, so rooms
# keep their exact grid coordinates after the terrain regenerates.
if [ -f "$INDEX" ]; then
  echo "Keeping path index: $INDEX ($(wc -l < "$INDEX" | tr -d ' ') rows)"
fi

# 2) Delete the live world (and its nether dimension, home of the Network
#    rooms) so they regenerate fresh on next boot. Handle both a namespaced
#    dimension folder and a top-level world folder.
for W in "$DIM_DIR/$WORLD_NAME" "$SERVER_DIR/$WORLD_NAME" \
         "$DIM_DIR/${WORLD_NAME}_nether" "$SERVER_DIR/${WORLD_NAME}_nether"; do
  if [ -d "$W" ]; then
    echo "Removing world: $W ($(du -sh "$W" 2>/dev/null | cut -f1))"
    rm -rf "$W"
  fi
done

# 3) Remove the old unused void dimension from earlier versions, if still present.
if [ -d "$DIM_DIR/deskmine" ]; then
  echo "Removing old unused void folder: $DIM_DIR/deskmine ($(du -sh "$DIM_DIR/deskmine" 2>/dev/null | cut -f1))"
  rm -rf "$DIM_DIR/deskmine"
fi

echo
echo "World reset. Relaunching..."
echo
exec "$DIR/start.command"
