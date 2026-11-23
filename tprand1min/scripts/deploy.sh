#!/usr/bin/env bash
# Build the mod and copy it into a Modrinth App instance's mods folder.
# Usage: scripts/deploy.sh [instance display name]   (default: "Mod Testing")
#        SKIP_BUILD=1 scripts/deploy.sh               (deploy the existing jar)
set -euo pipefail

cd "$(dirname "$0")/.."

INSTANCE_NAME="${1:-Mod Testing}"
MODRINTH_DIR="$HOME/Library/Application Support/ModrinthApp"
MOD_ID="tprand1min"

# --- Build -----------------------------------------------------------------
if [[ "${SKIP_BUILD:-0}" != "1" ]]; then
	scripts/build.sh
fi
jar="$(ls -t build/libs/*.jar 2>/dev/null | grep -v -- '-sources\.jar$' | head -n1 || true)"
[[ -n "$jar" ]] || { echo "error: no jar in build/libs; run scripts/build.sh" >&2; exit 1; }

# --- Resolve instance folder ---------------------------------------------------
# The Modrinth App's display name can differ from its folder name, so look it up
# in the app's database ("Mod Testing" lives in profiles/"Fabric 26.3").
folder="$(sqlite3 -readonly "$MODRINTH_DIR/app.db" \
	"select path from instances where name = '${INSTANCE_NAME//\'/\'\'}' limit 1" 2>/dev/null || true)"
[[ -n "$folder" ]] || folder="$INSTANCE_NAME"   # fall back to folder name
mods_dir="$MODRINTH_DIR/profiles/$folder/mods"
[[ -d "$mods_dir" ]] || { echo "error: instance '$INSTANCE_NAME' not found ($mods_dir)" >&2; exit 1; }

# --- Copy the mod, replacing older builds -----------------------------------------
find "$mods_dir" -maxdepth 1 -name "${MOD_ID}-*.jar" -delete
cp "$jar" "$mods_dir/"
echo "Deployed $(basename "$jar") -> $mods_dir"

# --- Make sure Fabric API is present (the mod depends on it) ------------------------
if ! ls "$mods_dir" | grep -qi 'fabric-api'; then
	fapi_version="$(grep '^fabric_api_version=' gradle.properties | cut -d= -f2)"
	fapi_jar="fabric-api-${fapi_version}.jar"
	echo "Fabric API missing; downloading $fapi_jar"
	curl -fsSL -o "$mods_dir/$fapi_jar" \
		"https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/${fapi_version}/${fapi_jar}"
fi

if pgrep -f "$folder" >/dev/null 2>&1; then
	echo "note: the instance appears to be running; restart it to load the new jar."
fi
