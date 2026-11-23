# Benchmarks

Measurements below are from one GitHub-hosted Linux x86_64 CI run. They are reproducible examples, not production VPS results. Native startup and idle-memory targets were not met. CI benchmarks the GraalVM native executable and a Temurin 21 JVM distribution on the same runner.

| Metric | Target | Measured |
| --- | ---: | ---: |
| Native executable size | Record exact size | 54,593,792 bytes |
| Distroless image virtual size | < 60 MB | 31,550,433 bytes |
| Process start to TCP listener | < 20 ms | 120 ms |
| Idle RSS | < 35 MB | 60,732 KiB |
| Peak RSS under load | Record measured value | 134,780 KiB |
| SIGTERM exit status | 0 | 0 |
| Redirect, 100 connections: P50 / P95 / P99 | Record measured values | 181.232 / 307.285 / 477.191 ms |
| Redirect, 100 connections: throughput | Record measured value | 608.209 requests/second |
| Redirect, 1,000 connections: P50 / P95 / P99 | Record measured values | 1,332.312 / 1,681.686 / 1,762.661 ms |
| Redirect, 1,000 connections: throughput | Record measured value | 785.455 requests/second |

The benchmark values currently shown are from a native-only run on 2026-11-23 with GraalVM Native Image 25.0.4, an AMD EPYC 9V74 80-Core Processor, and Linux kernel `7.0.0-1020-azure` on x86_64. The native-only run had a 100% OHA success rate at both concurrency levels. CI now also benchmarks a Temurin 21 JVM distribution on the same runner to support a like-for-like runtime comparison; those results must be recorded from the next complete run. Container validation found two runtime requirements: Debian 12's `libz.so.1` and a standalone healthcheck that works in GraalVM Native Image. The healthcheck now uses a loopback socket instead of the unavailable URL handler; the final container check is pending another CI run. The image-size number above predates the zlib addition and must be refreshed from the passing run.

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
docker stats --no-stream kurl
```

Do not compare results from different platforms as if they were equivalent. Record the exact command, build mode, and environment with every result.
The CI benchmark also sends `SIGTERM` and requires the native process to exit with status `0`.
The script writes machine-readable `oha` results to `build/oha-100.json` and `build/oha-1000.json` for native, and `build/oha-100-jvm.json` and `build/oha-1000-jvm.json` for JVM, including success rate, throughput, and P50/P95/P99 latency.
