# Benchmarks

Measurements below are from GitHub-hosted Linux x86_64 CI run `45183883880` (2026-11-23). They are reproducible examples, not production VPS results. Native startup and idle-memory targets were not met. CI benchmarks the GraalVM native executable and a Temurin 21 JVM distribution on the same runner.

| Metric | Target | Measured |
| --- | ---: | ---: |
| Native executable size | Record exact size | 54,790,400 bytes |
| Distroless image virtual size | < 60 MB | 31,739,703 bytes |
| Temurin 21 JRE image virtual size | Record measured value | 206,180,273 bytes |
| Native process start to TCP listener | < 20 ms | 90 ms |
| Native process start to first `/health` response | Record measured value | 100 ms |
| Native first `/health` request latency | Record measured value | 2.664 ms |
| Native idle RSS | < 35 MB | 60,828 KiB |
| Native peak RSS under load | Record measured value | 123,824 KiB |
| Native SIGTERM exit status | 0 | 0 |
| Native redirect, 100 connections: P50 / P95 / P99 | Record measured values | 91.900 / 119.219 / 136.046 ms |
| Native redirect, 100 connections: throughput | Record measured value | 1,334.537 requests/second |
| Native redirect, 1,000 connections: P50 / P95 / P99 | Record measured values | 665.266 / 857.926 / 922.132 ms |
| Native redirect, 1,000 connections: throughput | Record measured value | 1,473.057 requests/second |
| JVM process start to TCP listener | Record measured value | 628 ms |
| JVM process start to first `/health` response | Record measured value | 830 ms |
| JVM first `/health` request latency | Record measured value | 194.208 ms |
| JVM idle RSS | Record measured value | 128,740 KiB |
| JVM peak RSS under load | Record measured value | 320,912 KiB |
| JVM SIGTERM exit status | 0 | 0 |
| JVM redirect, 100 connections: P50 / P95 / P99 | Record measured values | 64.431 / 169.258 / 202.777 ms |
| JVM redirect, 100 connections: throughput | Record measured value | 1,288.154 requests/second |
| JVM redirect, 1,000 connections: P50 / P95 / P99 | Record measured values | 641.881 / 849.692 / 925.271 ms |
| JVM redirect, 1,000 connections: throughput | Record measured value | 1,522.440 requests/second |

The run used GraalVM Native Image 25.0.4, Temurin 21.0.13, an Intel Xeon 6975P-C, and Linux kernel `7.0.0-1016-aws` on x86_64. Both runtimes had a 100% OHA success rate at both concurrency levels. The native distroless image includes Debian 12's `libz.so.1`; its direct-socket healthcheck and graceful shutdown passed in CI. Native listener readiness and idle RSS remain above their targets. The measured JRE image is about 6.5 times the native image size on this runner. These are single-run CI results, not a statistically controlled performance comparison or production VPS measurements.

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
