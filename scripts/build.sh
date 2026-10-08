#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."
mkdir -p build/classes
find src/main/java -name '*.java' | sort > build/sources.txt
if [ "${1:-}" = "--tests" ]; then find src/test/java -name '*.java' | sort >> build/sources.txt; fi
javac --release 21 -encoding UTF-8 -Xlint:all -Werror -d build/classes @build/sources.txt
echo 'Build OK'
