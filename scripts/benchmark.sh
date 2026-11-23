#!/usr/bin/env bash
set -euo pipefail

runtime="${2:-native}"
if [[ "$runtime" == "jvm" ]]; then
  binary="${1:-build/install/kurl/bin/kurl}"
  artifact_bytes="$(du -sb build/install/kurl/lib | cut -f1)"
  runtime_version="$(java -version 2>&1 | head -n1)"
  oha_suffix="-jvm"
else
  binary="${1:-build/native/nativeCompile/kurl}"
  artifact_bytes="$(stat -c%s "$binary")"
  native_image="${GRAALVM_HOME:-}/bin/native-image"
  if [[ ! -x "$native_image" ]]; then
    native_image="$(command -v native-image || true)"
  fi
  if [[ -z "$native_image" ]]; then
    echo "Could not locate the GraalVM native-image executable" >&2
    exit 1
  fi
  runtime_version="$("$native_image" --version | head -n1)"
  oha_suffix=""
fi
port="${PORT:-18080}"
database="build/benchmark-data-${runtime}/kurl.db"
results="${BENCHMARK_RESULTS:-build/benchmark-results.md}"
server_log="build/benchmark-server-${runtime}.log"
mkdir -p "$(dirname "$database")"
rm -f "$database" "$database-shm" "$database-wal"

start_ns="$(date +%s%N)"
PORT="$port" \
DATABASE_PATH="$database" \
BASE_URL="http://127.0.0.1:$port" \
"$binary" >"$server_log" 2>&1 &
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
  cat "$server_log"
  echo "KURL did not become healthy within 2 seconds" >&2
  exit 1
fi

startup_ms="$(( (ready_ns - start_ns) / 1000000 ))"
curl --silent --show-error --fail "http://127.0.0.1:$port/health" >/dev/null
rss_kb="$(ps -o rss= -p "$pid" | tr -d ' ')"
ulimit -n 4096 || true

response="$(curl --silent --show-error --fail \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://example.org/benchmark"}' \
  "http://127.0.0.1:$port/api/shorten")"
slug="$(jq -r '.slug' <<<"$response")"
peak_rss_file="build/benchmark-rss-${runtime}-samples.txt"
rm -f "$peak_rss_file"
(
  while kill -0 "$pid" 2>/dev/null; do
    ps -o rss= -p "$pid" | tr -d ' ' >>"$peak_rss_file"
    sleep 0.1
  done
) &
monitor_pid=$!

run_load_test() {
  local connections="$1"
  local output="build/oha-${connections}${oha_suffix}.json"
  oha --no-tui --output-format json -o "$output" \
    -z 15s -c "$connections" "http://127.0.0.1:$port/$slug"
  jq -e '.metrics.success_rate == 1' "$output" >/dev/null
}

run_load_test 100
run_load_test 1000
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
  echo "# ${runtime^^} benchmark run"
  echo
  echo "- Date (UTC): $(date -u +%FT%TZ)"
  echo "- OS/kernel: $(uname -a)"
  echo "- CPU: $(grep -m1 'model name' /proc/cpuinfo | cut -d: -f2- | xargs)"
  echo "- Runtime: $runtime_version"
  echo "- Artifact: $binary ($artifact_bytes bytes)"
  echo "- TCP listener readiness: ${startup_ms} ms (process start to first detected listening socket; includes scheduler and polling resolution)"
  echo "- Idle RSS: ${rss_kb} KiB"
  echo "- SIGTERM exit status: $shutdown_status"
  echo
  echo "- Peak RSS during load: ${peak_rss_kb} KiB"
  for connections in 100 1000; do
    output="build/oha-${connections}${oha_suffix}.json"
    echo
    echo "## Redirect load test: ${connections} concurrent clients"
    echo
    echo "- Success rate: $(jq -r '.metrics.success_rate' "$output")"
    echo "- Throughput: $(jq -r '.metrics.requests_per_sec' "$output") requests/second"
    echo "- Latency P50 / P95 / P99: $(jq -r '.metrics.latency_ms.p50' "$output") / $(jq -r '.metrics.latency_ms.p95' "$output") / $(jq -r '.metrics.latency_ms.p99' "$output") ms"
  done
} >"$results"

cat "$results"
