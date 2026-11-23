# Benchmarks

No benchmark figures are reported yet. The values below are targets from the project brief, not measurements.

| Metric | Target | Measured |
| --- | ---: | --- |
| Native executable size | Record exact size | Not measured |
| Container image size | < 60 MB | Not measured |
| Cold start to listening | < 20 ms | Not measured |
| Idle RSS | < 35 MB | Not measured |
| Redirect P50 / P95 / P99 | Record measured values | Not measured |
| Throughput | Record requests/second | Not measured |

## Reproduction

Record CPU model, OS/kernel, GraalVM version, Gradle version, and Docker version with each run.

```sh
./gradlew nativeCompile
ls -lh build/native/nativeCompile/kurl
time ./build/native/nativeCompile/kurl
docker image inspect ghcr.io/toptoppy/kurl:latest --format '{{.Size}}'
docker stats --no-stream kurl
wrk -t4 -c100 -d30s http://localhost:8080/<test-slug>
```

Do not compare results from different platforms as if they were equivalent. Record the exact command, build mode, and environment with every result.
