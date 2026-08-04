#!/usr/bin/env sh
# run-client.sh — POSIX (macOS/Linux) equivalent of run-client.bat.
# Launches the OmniChest (Stonecutter included build) client for a given MC by
# running the version-node task :<MC>:runClient inside mods/omnichest.
#
# JAVA_HOME is intentionally NOT hardcoded, and you do not need to set it.
# gradle/gradle-daemon-jvm.properties (tracked) pins the daemon JVM, and the
# per-version toolchains (21 for 1.21.x, 25 for 26.1+) are resolved by Gradle.
# Anything missing is downloaded on first use. See README.
#
# Usage:
#   ./run-client.sh            # launch recommended MC 26.1.2
#   ./run-client.sh 1.21.11    # launch a specific MC
set -e
MC="${1:-26.1.2}"
cd "$(dirname "$0")/mods/omnichest"
exec ./gradlew ":${MC}:runClient"
