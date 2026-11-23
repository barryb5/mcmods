#!/usr/bin/env bash
# Build mcutils and publish it to the local Maven repo (~/.m2) so other mods can use it.
set -euo pipefail
cd "$(dirname "$0")/.."
JAVA_HOME="$(/usr/libexec/java_home -v 25)" || { echo "error: JDK 25 not found" >&2; exit 1; }
export JAVA_HOME
./gradlew publishToMavenLocal "$@"
echo "Published com.mcutils:mcutils:$(grep '^version=' gradle.properties | cut -d= -f2) to ~/.m2"
