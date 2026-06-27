#!/usr/bin/env sh
# build-mod.sh — POSIX (macOS/Linux) equivalent of build-mod.bat.
# Builds the recommended MC build for every mod (root buildRecommended task).
#
# JAVA_HOME is intentionally NOT hardcoded here. Provide JDK 21 (1.21.x) and
# JDK 25 (26.1+) via Gradle toolchain auto-detection, or register their paths in
# ~/.gradle/gradle.properties (org.gradle.java.installations.paths). See README.
#
# Usage:
#   ./build-mod.sh            # build recommended (MC 26.1.2)
#   ./build-mod.sh build26_1  # pass any root build task through
set -e
cd "$(dirname "$0")"
exec ./gradlew buildRecommended "$@"
