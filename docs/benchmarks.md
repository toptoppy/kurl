# Benchmarks

Measurements below are from one GitHub-hosted Linux x86_64 CI run. They are reproducible examples, not production VPS results. Native startup and idle-memory targets were not met.

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

The benchmark ran on 2026-11-23 with GraalVM Native Image 25.0.4, an AMD EPYC 9V74 80-Core Processor, and Linux kernel `7.0.0-1020-azure` on x86_64. OHA reported a 100% success rate at both concurrency levels. The first distroless smoke test found the image was missing `libz.so.1`; the Dockerfile now includes Debian 12's zlib runtime library. The final container check is pending another CI run.

## Reproduction

Record CPU model, OS/kernel, GraalVM version, Gradle version, and Docker version with each run.

```sh
./gradlew nativeCompile
brew install oha # or install oha from its official release
bash scripts/benchmark.sh
ls -lh build/native/nativeCompile/kurl
time ./build/native/nativeCompile/kurl
docker image inspect ghcr.io/toptoppy/kurl:latest --format '{{.Size}}'
docker stats --no-stream kurl
```

Do not compare results from different platforms as if they were equivalent. Record the exact command, build mode, and environment with every result.
The CI benchmark also sends `SIGTERM` and requires the native process to exit with status `0`.
The script writes machine-readable `oha` results to `build/oha-100.json` and `build/oha-1000.json`, including success rate, throughput, and P50/P95/P99 latency.
