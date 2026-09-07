#!/usr/bin/env bash
# The four measured runs, in the order RESULTS.md reports them. Each one empties and rebuilds the
# ten thousand accounts first, so the fourth ramp reads indexes the same depth as the first and the
# only thing that differs between two runs is the one thing being compared.
#
# Expects the compose stack up with PostgreSQL published on --pg-port, and k6 on the PATH.
set -euo pipefail

PG_PORT="${PG_PORT:-5433}"
cd "$(dirname "$0")/.."

for run in "u-read-committed u " "h-read-committed h " \
           "u-serializable u serializable" "h-serializable h serializable"; do
  read -r name scenario profiles <<<"$run"
  echo "=== $name ==="
  python load/run.py --name "$name" --scenario "$scenario" --profiles "$profiles" \
    --pg-port "$PG_PORT" --reseed
done
