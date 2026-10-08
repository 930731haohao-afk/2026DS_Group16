#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."
sh scripts/build.sh --tests
java -ea -cp build/classes edu.nccu.ai.TestSuite
