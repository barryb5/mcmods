#!/usr/bin/env bash
# Build the mod jar with JDK 25 (required by Minecraft 26.x).
# Usage: scripts/build.sh [extra gradle args...]
set -euo pipefail

cd "$(dirname "$0")/.."

# Locate JDK 25. macOS: ask java_home. Elsewhere: $JAVA_HOME, the javac on PATH, then /usr/lib/jvm.
find_jdk25() {
	if [[ -x /usr/libexec/java_home ]]; then
		/usr/libexec/java_home -v 25 2>/dev/null
		return
	fi
	local home
	for home in "${JAVA_HOME:-}" \
		"$(dirname "$(dirname "$(readlink -f "$(command -v javac 2>/dev/null)" 2>/dev/null)")")" \
		/usr/lib/jvm/*25*; do
		if [[ -n "$home" && -x "$home/bin/javac" ]] && "$home/bin/javac" -version 2>&1 | grep -qE '^javac 25(\.|$)'; then
			echo "$home"
			return
		fi
	done
	return 1
}
if ! JAVA_HOME="$(find_jdk25)"; then
	echo "error: JDK 25 not found (macOS: see /usr/libexec/java_home -V; Linux: install openjdk-25-jdk or set JAVA_HOME)" >&2
	exit 1
fi
export JAVA_HOME

# Republish the shared library first so this build picks up its latest changes.
if [[ -x ../mcutils/scripts/publish.sh ]]; then
	../mcutils/scripts/publish.sh -q
fi

./gradlew build "$@"

# Print the path of the built mod jar (excluding the -sources jar).
jar="$(ls -t build/libs/*.jar | grep -v -- '-sources\.jar$' | head -n1)"
echo "Built: $jar"
