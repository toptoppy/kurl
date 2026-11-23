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
  if ss -Hltn "sport = :$port" | grep -q LISTEN; then
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
curl --silent --show-error --fail "http://127.0.0.1:$port/health" >/dev/null
binary_bytes="$(stat -c%s "$binary")"
rss_kb="$(ps -o rss= -p "$pid" | tr -d ' ')"
ulimit -n 4096 || true
native_image="${GRAALVM_HOME:-}/bin/native-image"
if [[ ! -x "$native_image" ]]; then
  native_image="$(command -v native-image || true)"
fi
if [[ -z "$native_image" ]]; then
  echo "Could not locate the GraalVM native-image executable" >&2
  exit 1
fi

response="$(curl --silent --show-error --fail \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://example.org/benchmark"}' \
  "http://127.0.0.1:$port/api/shorten")"
slug="$(jq -r '.slug' <<<"$response")"
peak_rss_file="build/benchmark-rss-samples.txt"
rm -f "$peak_rss_file"
(
  while kill -0 "$pid" 2>/dev/null; do
    ps -o rss= -p "$pid" | tr -d ' ' >>"$peak_rss_file"
    sleep 0.1
  done
) &
monitor_pid=$!
wrk_100="$(wrk --latency -t4 -c100 -d15s "http://127.0.0.1:$port/$slug")"
wrk_1000="$(wrk --latency -t4 -c1000 -d15s "http://127.0.0.1:$port/$slug")"
kill "$monitor_pid" 2>/dev/null || true
wait "$monitor_pid" 2>/dev/null || true
peak_rss_kb="$(sort -nr "$peak_rss_file" | head -n1)"
kill -TERM "$pid"
set +e
wait "$pid"
shutdown_status=$?
set -e
pid=""
trap - EXIT
if [[ "$shutdown_status" -ne 0 ]]; then
  echo "KURL exited with status $shutdown_status after SIGTERM" >&2
  exit 1
fi

{
  echo "# Native benchmark run"
  echo
  echo "- Date (UTC): $(date -u +%FT%TZ)"
  echo "- OS/kernel: $(uname -a)"
  echo "- CPU: $(grep -m1 'model name' /proc/cpuinfo | cut -d: -f2- | xargs)"
  echo "- GraalVM: $("$native_image" --version | head -n1)"
  echo "- Binary: $binary ($binary_bytes bytes)"
  echo "- TCP listener readiness: ${startup_ms} ms (process start to first detected listening socket; includes scheduler and polling resolution)"
  echo "- Idle RSS: ${rss_kb} KiB"
  echo "- SIGTERM exit status: $shutdown_status"
  echo
  echo "- Peak RSS during load: ${peak_rss_kb} KiB"
  echo
  echo "## Redirect load test: 100 concurrent clients"
  echo
  echo '```text'
  echo "$wrk_100"
  echo '```'
  echo
  echo "## Redirect load test: 1,000 concurrent clients"
  echo
  echo '```text'
  echo "$wrk_1000"
  echo '```'
} >"$results"

cat "$results"
