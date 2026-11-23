#!/usr/bin/env bash
set -euo pipefail

binary="${1:-build/native/nativeCompile/kurl}"
port="${PORT:-18080}"
database="build/benchmark-data/kurl.db"
results="build/benchmark-results.md"
mkdir -p "$(dirname "$database")"
rm -f "$database" "$database-shm" "$database-wal"

start_ns="$(date +%s%N)"
PORT="$port" \
DATABASE_PATH="$database" \
BASE_URL="http://127.0.0.1:$port" \
"$binary" >build/benchmark-server.log 2>&1 &
pid=$!
cleanup() {
  kill "$pid" 2>/dev/null || true
  wait "$pid" 2>/dev/null || true
}
trap cleanup EXIT

ready_ns=""
for _ in $(seq 1 200); do
  if curl --silent --fail "http://127.0.0.1:$port/health" >/dev/null; then
    ready_ns="$(date +%s%N)"
    break
  fi
  sleep 0.01
done
if [[ -z "$ready_ns" ]]; then
  cat build/benchmark-server.log
  echo "KURL did not become healthy within 2 seconds" >&2
  exit 1
fi

startup_ms="$(( (ready_ns - start_ns) / 1000000 ))"
binary_bytes="$(stat -c%s "$binary")"
rss_kb="$(ps -o rss= -p "$pid" | tr -d ' ')"
native_image="${GRAALVM_HOME:-}/bin/native-image"
if [[ ! -x "$native_image" ]]; then
  native_image="$(command -v native-image || true)"
fi

response="$(curl --silent --show-error --fail \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://example.org/benchmark"}' \
  "http://127.0.0.1:$port/api/shorten")"
slug="$(jq -r '.slug' <<<"$response")"
wrk_output="$(wrk --latency -t4 -c100 -d15s "http://127.0.0.1:$port/$slug")"

{
  echo "# Native benchmark run"
  echo
  echo "- Date (UTC): $(date -u +%FT%TZ)"
  echo "- OS/kernel: $(uname -a)"
  echo "- CPU: $(grep -m1 'model name' /proc/cpuinfo | cut -d: -f2- | xargs)"
  echo "- GraalVM: $("$native_image" --version | head -n1)"
  echo "- Binary: $binary ($binary_bytes bytes)"
  echo "- HTTP health readiness: ${startup_ms} ms (process start to first successful curl health request; includes client/process scheduling)"
  echo "- Idle RSS: ${rss_kb} KiB"
  echo
  echo "## Redirect load test"
  echo
  echo '```text'
  echo "$wrk_output"
  echo '```'
} >"$results"

cat "$results"
