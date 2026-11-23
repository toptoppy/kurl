# Can Kotlin make a tiny URL shortener?

*Working draft for TopLog. Do not publish until the container healthcheck is resolved and the article is reviewed in the KURL Notebook.*

Kotlin is a comfortable choice for a small HTTP service, but the usual JVM runtime can feel like a lot to carry for a URL shortener. Could GraalVM Native Image make a Ktor service fast to start and small enough to deploy as a single container?

KURL is a small experiment in answering that question with measurements rather than assumptions.

## A small, direct request path

KURL serves a short-link creation endpoint, a redirect endpoint, statistics, and a health check. Ktor with Netty handles HTTP. A small JDBC repository stores mappings and click counts in SQLite, with WAL enabled for concurrent reads and writes. There is no ORM or database server to operate.

The service does not fetch submitted destinations. It validates schemes and host addresses when a link is created, rejects private and loopback destinations, and checks that a destination is not the service's own public URL. An optional API key protects link creation and statistics; health and redirects stay public.

The deployment image uses a distroless base and runs as a non-root UID. SQLite data lives in a mounted `/app/data` directory.

## Native Image required a real runtime test

The first lesson was that a successful native compilation is not evidence that the executable works.

Ktor's Netty native-image metadata uses `SharedArenaSupport`, which the GraalVM 21 toolchain rejected. KURL keeps application bytecode and Gradle on Java 21, while CI uses GraalVM 25 for the native-image compiler.

The next failure appeared only after the native executable started: Ktor's automatic content-negotiation lookup could not find the JSON model serializers. KURL now calls the generated kotlinx-serialization codecs directly at the HTTP boundary. SQLite JDBC also contains native libraries for multiple platforms, so the Linux x86_64 image includes only the matching JNI library.

That is why the CI pipeline exercises the executable itself: it checks health, creates a link, runs concurrent redirects, and sends `SIGTERM` to verify a clean exit. The image builds, but its container healthcheck is still under investigation.

## First measurements

The benchmark ran on a GitHub-hosted x86_64 Linux VM with an AMD EPYC 9V74 CPU and GraalVM Native Image 25.0.4. This is a CI measurement, not a production VPS result.

| Metric | First CI measurement | Project target |
| --- | ---: | ---: |
| Native executable | 54,593,792 bytes | Record |
| Distroless image virtual size | 31,550,433 bytes | < 60 MB |
| Process start to TCP listener | 120 ms | < 20 ms |
| Idle RSS | 60,732 KiB | < 35 MB |
| Peak RSS during load | 134,780 KiB | Record |
| SIGTERM exit status | 0 | 0 |

The image fit the size target, and the process shut down successfully. The measured listener time and idle memory did **not** meet the original targets. These numbers should not be presented as proof that KURL is a sub-20-ms or sub-35-MB service.

OHA reported a 100% success rate at both concurrency levels. At 100 connections, throughput was 608.209 requests/second with P50/P95/P99 latency of 181.232/307.285/477.191 ms. At 1,000 connections, throughput was 785.455 requests/second with P50/P95/P99 latency of 1,332.312/1,681.686/1,762.661 ms.

## What the experiment has shown so far

Kotlin can produce a standalone native Ktor service with a compact distroless image, SQLite storage, and a tested shutdown path. It also brings real costs: native metadata and JNI resources need deliberate handling, and the first measured startup and memory figures miss the ambitious goals.

The useful result is not “native is always smaller.” It is that a small service can be built and tested this way, while its actual resource profile remains visible and reproducible. The next step is to compare the full percentile results on the same runner and then measure the deployed VPS separately.

**Repository:** https://github.com/toptoppy/kurl
