# Benchmarks

Measurements below are from GitHub-hosted Linux x86_64 CI run `45985608734` (2026-11-27). They are reproducible examples, not production VPS results. Native startup and idle-memory targets were not met. CI benchmarks the GraalVM native executable and a Temurin 21 JVM distribution on the same runner. This run uses the Ktor CIO engine; the previous verified run (`45183883880`, 2026-11-23) used the Netty engine, so cross-run deltas mix an engine change with platform noise.

| Metric | Target | Measured |
| --- | ---: | ---: |
| Native executable size | Record exact size | 42,404,096 bytes |
| Distroless image virtual size | < 60 MB | 26,804,778 bytes |
| Temurin 21 JRE image virtual size | Record measured value | 174,346,285 bytes |
| Native process start to TCP listener | < 20 ms | 52 ms |
| Native process start to first `/health` response | Record measured value | 60 ms |
| Native first `/health` request latency | Record measured value | 1.397 ms |
| Native idle RSS | < 35 MB | 52,268 KiB |
| Native peak RSS under load | Record measured value | 115,484 KiB |
| Native SIGTERM exit status | 0 | 0 |
| Native redirect, 100 connections: P50 / P95 / P99 | Record measured values | 56.966 / 74.046 / 81.012 ms |
| Native redirect, 100 connections: throughput | Record measured value | 1,802.396 requests/second |
| Native redirect, 1,000 connections: P50 / P95 / P99 | Record measured values | 556.006 / 780.337 / 1,567.562 ms |
| Native redirect, 1,000 connections: throughput | Record measured value | 1,803.219 requests/second |
| JVM process start to TCP listener | Record measured value | 486 ms |
| JVM process start to first `/health` response | Record measured value | 556 ms |
| JVM first `/health` request latency | Record measured value | 62.644 ms |
| JVM idle RSS | Record measured value | 109,016 KiB |
| JVM peak RSS under load | Record measured value | 358,896 KiB |
| JVM SIGTERM exit status | 0 | 0 |
| JVM redirect, 100 connections: P50 / P95 / P99 | Record measured values | 48.796 / 106.058 / 166.767 ms |
| JVM redirect, 100 connections: throughput | Record measured value | 1,879.671 requests/second |
| JVM redirect, 1,000 connections: P50 / P95 / P99 | Record measured values | 391.551 / 529.824 / 653.667 ms |
| JVM redirect, 1,000 connections: throughput | Record measured value | 2,462.213 requests/second |

The run used GraalVM Native Image 25.0.4, Temurin 21.0.13, an Intel Xeon 6975P-C, and Linux kernel `7.0.0-1016-aws` on x86_64. Both runtimes had a 100% OHA success rate at both concurrency levels. The native distroless image includes Debian 12's `libz.so.1`; its direct-socket healthcheck and graceful shutdown passed in CI. Compared with the previous verified run on the same runner class (`45183883880`, Netty engine), the CIO engine reduced the native binary from 54,790,400 to 42,404,096 bytes, the distroless image from 31,739,703 to 26,804,778 bytes, listener readiness from 90 to 52 ms, and idle RSS from 60,828 to 52,268 KiB, while redirect throughput at 100 connections rose from 1,334.5 to 1,802.4 requests/second. Listener readiness and idle RSS remain above their targets. These are single-run CI results, not a statistically controlled performance comparison or production VPS measurements.

## Reproduction

Record CPU model, OS/kernel, GraalVM version, Gradle version, and Docker version with each run.

```sh
./gradlew nativeCompile
./gradlew installDist
brew install oha # or install oha from its official release
BENCHMARK_RESULTS=build/benchmark-native-results.md bash scripts/benchmark.sh
BENCHMARK_RESULTS=build/benchmark-jvm-results.md \
  bash scripts/benchmark.sh build/install/kurl/bin/kurl jvm
ls -lh build/native/nativeCompile/kurl
time ./build/native/nativeCompile/kurl
docker image inspect ghcr.io/toptoppy/kurl:latest --format '{{.Size}}'
docker build --file benchmarks/Dockerfile.jvm --tag kurl:jvm-ci .
docker image inspect kurl:jvm-ci --format '{{.Size}}'
docker stats --no-stream kurl
```

Do not compare results from different platforms as if they were equivalent. Record the exact command, build mode, and environment with every result.
The CI benchmark also sends `SIGTERM` and requires the native process to exit with status `0`.
The script records TCP listener readiness, time from process start to its first successful `/health` response, and that first request's latency. It writes machine-readable `oha` results to `build/oha-100.json` and `build/oha-1000.json` for native, and `build/oha-100-jvm.json` and `build/oha-1000-jvm.json` for JVM, including success rate, throughput, and P50/P95/P99 latency.

## Production VPS spot-check (2026-11-27, Hetzner, linux/amd64)

Measured on the deployment VPS (`toppy-server`) with the 0.1.0 binary (Netty engine), using the same binary outside Docker under Debian 12 with an uptime-clock readiness probe and `/proc` inspection: listener readiness 40.0 ms, idle Rss 49,672 KiB (Pss 48,355 KiB; Pss_Anon 8,472 KiB; Pss_Shmem 38,200 KiB; Pss_File 1,683 KiB), SIGTERM exit 0, and `smaps` showing the binary's own code and image-heap segments (not the Java heap) as the dominant resident memory. Heap cap `-XX:MaxHeapSize=32m` changed idle RSS by less than measurement noise. Re-measure with the CIO 0.1.1 binary before quoting production numbers.
