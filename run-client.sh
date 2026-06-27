#!/usr/bin/env sh
# run-client.sh — POSIX (macOS/Linux) equivalent of run-client.bat.
# Launches the OmniChest (Stonecutter included build) client for a given MC by
# running the version-node task :<MC>:runClient inside mods/omnichest.
#
# JAVA_HOME is intentionally NOT hardcoded. 26.1.x needs the Gradle daemon on
# JDK 25; 1.21.x needs JDK 21. Supply both via toolchain auto-detection or
# org.gradle.java.installations.paths in ~/.gradle/gradle.properties. See README.
#
# Usage:
#   ./run-client.sh            # launch recommended MC 26.1.2
#   ./run-client.sh 1.21.11    # launch a specific MC
set -e
MC="${1:-26.1.2}"
cd "$(dirname "$0")/mods/omnichest"
exec ./gradlew ":${MC}:runClient"
