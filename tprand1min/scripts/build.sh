#!/usr/bin/env bash
# Build the mod jar with JDK 25 (required by Minecraft 26.x).
# Usage: scripts/build.sh [extra gradle args...]
set -euo pipefail

cd "$(dirname "$0")/.."

if ! JAVA_HOME="$(/usr/libexec/java_home -v 25 2>/dev/null)"; then
	echo "error: JDK 25 not found (see /usr/libexec/java_home -V)" >&2
	exit 1
fi
export JAVA_HOME

./gradlew build "$@"

# Print the path of the built mod jar (excluding the -sources jar).
jar="$(ls -t build/libs/*.jar | grep -v -- '-sources\.jar$' | head -n1)"
echo "Built: $jar"
