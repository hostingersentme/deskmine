#!/usr/bin/env bash
# Deskmine — local Paper server setup (macOS).
# Downloads Paper, installs the Deskmine plugin, and starts the server.
# Run from the Deskmine project folder:  ./server-setup.sh
set -euo pipefail

PAPER_VERSION="26.1.2"
# Pinned fallback (build 72); the script tries to fetch the latest build first.
FALLBACK_URL="https://fill-data.papermc.io/v1/objects/0555a0b0468a5198d8fb1a16e1f9e95c81a917a2dc8f2e09867b4044742f6401/paper-26.1.2-72.jar"

DIR="$(cd "$(dirname "$0")" && pwd)"
SERVER="$DIR/server"
PLUGIN_PROJECT="$DIR/deskmine-plugin"

# --- Java 25+ check ----------------------------------------------------------
JAVA_BIN=""
for candidate in java /opt/homebrew/opt/openjdk/bin/java /usr/local/opt/openjdk/bin/java; do
  if "$candidate" -version 2>&1 | head -1 | grep -Eq '"(2[5-9]|[3-9][0-9])'; then
    JAVA_BIN="$(command -v "$candidate" 2>/dev/null || printf '%s' "$candidate")"
    break
  fi
done
if [ -z "$JAVA_BIN" ]; then
  echo "Paper $PAPER_VERSION requires Java 25+."
  echo "Install it with:  brew install openjdk   (or:  brew install --cask temurin)"
  exit 1
fi
echo "Using Java: $JAVA_BIN"
JAVA_HOME="$("$JAVA_BIN" -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java.home = //p' | head -1)"
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

mkdir -p "$SERVER/plugins"

set_prop() {
  local key="$1"
  local value="$2"
  local file="$SERVER/server.properties"
  if grep -q "^${key}=" "$file" 2>/dev/null; then
    sed -i.bak "s|^${key}=.*|${key}=${value}|" "$file"
    rm -f "$file.bak"
  else
    printf '%s=%s\n' "$key" "$value" >> "$file"
  fi
}

get_prop() {
  local key="$1"
  local file="$SERVER/server.properties"
  sed -n "s/^${key}=//p" "$file" 2>/dev/null | tail -1
}

lan_ip() {
  ipconfig getifaddr en0 2>/dev/null \
    || ipconfig getifaddr en1 2>/dev/null \
    || echo "your Mac's LAN IP"
}

# --- Download Paper ----------------------------------------------------------
if [ ! -f "$SERVER/paper.jar" ]; then
  echo "Fetching latest Paper $PAPER_VERSION build info..."
  URL="$(curl -fsSL "https://fill.papermc.io/v3/projects/paper/versions/$PAPER_VERSION/builds/latest" \
    | python3 -c 'import sys,json; d=json.load(sys.stdin); print(d["downloads"]["server:default"]["url"])' \
    2>/dev/null || true)"
  if [ -z "${URL:-}" ]; then
    echo "Could not query the downloads API; using pinned build 72."
    URL="$FALLBACK_URL"
  fi
  echo "Downloading $URL"
  curl -fL -o "$SERVER/paper.jar" "$URL"
fi

# --- Build & install plugin --------------------------------------------------
find "$PLUGIN_PROJECT/build/libs" -maxdepth 1 -type f -name 'deskmine-*.jar' -delete 2>/dev/null || true
if [ -f "$PLUGIN_PROJECT/gradlew" ]; then
  (cd "$PLUGIN_PROJECT" && ./gradlew -q build)
elif command -v gradle >/dev/null 2>&1; then
  (cd "$PLUGIN_PROJECT" && gradle -q build)
else
  echo "Gradle not found. Install with:  brew install gradle"
  exit 1
fi
find "$SERVER/plugins" -maxdepth 1 -type f -name 'deskmine-*.jar' -delete
cp "$PLUGIN_PROJECT"/build/libs/deskmine-*.jar "$SERVER/plugins/"
echo "Plugin installed."

# --- EULA --------------------------------------------------------------------
if [ ! -f "$SERVER/eula.txt" ] || ! grep -q "eula=true" "$SERVER/eula.txt"; then
  echo
  echo "Minecraft server software requires accepting the Minecraft EULA:"
  echo "  https://aka.ms/MinecraftEULA"
  read -r -p "Accept? [y/N] " reply
  if [[ "$reply" =~ ^[Yy]$ ]]; then
    echo "eula=true" > "$SERVER/eula.txt"
  else
    echo "EULA not accepted; the server cannot start."
    exit 1
  fi
fi

# --- server.properties (first run only) --------------------------------------
if [ ! -f "$SERVER/server.properties" ]; then
  cat > "$SERVER/server.properties" <<'EOF'
motd=Deskmine — your Mac as a Minecraft world
server-ip=127.0.0.1
online-mode=false
enforce-secure-profile=false
gamemode=survival
force-gamemode=true
difficulty=peaceful
spawn-protection=0
level-type=minecraft\:flat
generate-structures=false
view-distance=10
EOF
fi

# --- Run ---------------------------------------------------------------------
echo
SERVER_IP="$(get_prop "server-ip")"
if [ "$SERVER_IP" = "127.0.0.1" ] || [ "$SERVER_IP" = "localhost" ]; then
  echo "Starting Paper. Join with a Minecraft $PAPER_VERSION client at:  localhost"
else
  echo "Starting Paper. Join locally at:  localhost"
  echo "LAN players can join at:  $(lan_ip):25565"
fi
echo "(stop with the 'stop' command in the console)"
cd "$SERVER"
exec "$JAVA_BIN" -Xms2G -Xmx2G -jar paper.jar --nogui
