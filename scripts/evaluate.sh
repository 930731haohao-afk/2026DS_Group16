#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."
sh scripts/build.sh
if [ "$#" -eq 0 ]; then set -- prepare; fi
exec java -cp build/classes edu.nccu.ai.EvaluationRunner "$@"
