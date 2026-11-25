#!/usr/bin/env bash
# Build mcutils and publish it to the local Maven repo (~/.m2) so other mods can use it.
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
JAVA_HOME="$(find_jdk25)" || { echo "error: JDK 25 not found" >&2; exit 1; }
export JAVA_HOME
./gradlew publishToMavenLocal "$@"
echo "Published com.mcutils:mcutils:$(grep '^version=' gradle.properties | cut -d= -f2) to ~/.m2"
