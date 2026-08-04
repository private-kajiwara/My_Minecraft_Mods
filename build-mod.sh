#!/usr/bin/env sh
# build-mod.sh — POSIX (macOS/Linux) equivalent of build-mod.bat.
# Builds the recommended MC build for every mod (root buildRecommended task).
#
# JAVA_HOME is intentionally NOT hardcoded here, and you do not need to set it.
# gradle/gradle-daemon-jvm.properties (tracked) pins the daemon JVM, and the
# per-version toolchains (21 for 1.21.x, 25 for 26.1+) are resolved by Gradle.
# Anything missing is downloaded on first use. See README.
#
# Usage:
#   ./build-mod.sh            # build recommended (MC 26.1.2)
#   ./build-mod.sh build26_1  # pass any root build task through
set -e
cd "$(dirname "$0")"
exec ./gradlew buildRecommended "$@"
