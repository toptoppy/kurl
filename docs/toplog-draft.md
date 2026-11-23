# Can Kotlin make a tiny URL shortener?

*Working draft for TopLog. Do not publish until the updated container passes health/shutdown checks, image size is refreshed, and the article is reviewed in the KURL Notebook.*

For a tiny HTTP utility, developers often reach for Go's single-binary deployment or Python's quick scripting loop. Kotlin offers a mature ecosystem and concise server code, but the usual JVM runtime can feel like a lot to carry for a URL shortener. Could GraalVM Native Image make a Ktor service fast to start and small enough to deploy as a single container?

KURL is a small experiment in answering that question with measurements rather than assumptions.

## A small, direct request path

KURL serves a short-link creation endpoint, a redirect endpoint, statistics, and a health check. Ktor with Netty handles HTTP. A small JDBC repository stores mappings and click counts in SQLite, with WAL enabled for concurrent reads and writes. There is no ORM or database server to operate.

The service does not fetch submitted destinations. It validates schemes and host addresses when a link is created, rejects private and loopback destinations, and checks that a destination is not the service's own public URL. An optional API key protects link creation and statistics; health and redirects stay public.

The deployment image uses a distroless base and runs as a non-root UID. SQLite data lives in a mounted `/app/data` directory.

## Create and deploy a link

The create endpoint accepts JSON and returns the generated short URL:

```sh
curl -sS http://localhost:8080/api/shorten \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://example.com/a/long/path"}'
```

For a small Compose deployment, keep the database on a mounted volume and inject the optional API key from the host environment:

```yaml
services:
  kurl:
    image: ghcr.io/toptoppy/kurl:latest
    restart: unless-stopped
    ports:
      - "8080:8080"
    environment:
      BASE_URL: https://s.example.com
      API_KEY: ${KURL_API_KEY}
      DATABASE_PATH: /app/data/kurl.db
    volumes:
      - ./data:/app/data
```

The repository's [deployment template](../deployment/docker-compose.yml) also joins the existing Caddy Docker network and includes the image healthcheck.

## Native Image required a real runtime test

The first lesson was that a successful native compilation is not evidence that the executable works.

Ktor's Netty native-image metadata uses `SharedArenaSupport`, which the GraalVM 21 toolchain rejected. KURL keeps application bytecode and Gradle on Java 21, while CI uses GraalVM 25 for the native-image compiler.

The next failure appeared only after the native executable started: Ktor's automatic content-negotiation lookup could not find the JSON model serializers. KURL now calls the generated kotlinx-serialization codecs directly at the HTTP boundary. SQLite JDBC also contains native libraries for multiple platforms, so the Linux x86_64 image includes only the matching JNI library.

That is why the CI pipeline exercises the executable itself: it checks health, creates a link, runs concurrent redirects, and sends `SIGTERM` to verify a clean exit. Container testing found two gaps beyond native compilation: the image lacked `libz.so.1`, and the standalone healthcheck relied on a URL handler unavailable in the native image. The image now includes zlib, and the healthcheck uses a direct loopback socket. Both the health check and graceful shutdown passed in CI.

## First measurements

The latest complete benchmark ran on a GitHub-hosted x86_64 Linux VM with an Intel Xeon 6975P-C CPU, GraalVM Native Image 25.0.4, and Temurin 21.0.13. Both runtimes were measured on the same runner. This is a CI measurement, not a production VPS result.

| Metric | Native | Temurin 21 JVM | Project target |
| --- | ---: | ---: |
| Runtime artifact | Executable: 54,790,400 bytes | Distribution libraries: 44,251,781 bytes (JRE excluded) | Record |
| Container image virtual size | 31,739,703 bytes | 206,180,273 bytes | Native: < 60 MB |
| Process start to TCP listener | 90 ms | 628 ms | Native: < 20 ms |
| Process start to first health response | 100 ms | 830 ms | Record |
| First health request latency | 2.664 ms | 194.208 ms | Record |
| Idle RSS | 60,828 KiB | 128,740 KiB | Native: < 35 MB |
| Peak RSS during load | 123,824 KiB | 320,912 KiB | Record |
| SIGTERM exit status | 0 | 0 | 0 |
| 100-connection redirect throughput | 1,334.537 req/s | 1,288.154 req/s | Record |
| 100-connection redirect P50/P95/P99 | 91.900/119.219/136.046 ms | 64.431/169.258/202.777 ms | Record |
| 1,000-connection redirect throughput | 1,473.057 req/s | 1,522.440 req/s | Record |
| 1,000-connection redirect P50/P95/P99 | 665.266/857.926/922.132 ms | 641.881/849.692/925.271 ms | Record |

The native image fit the size target, and both processes shut down successfully. Both runtimes had 100% successful requests at both tested concurrency levels. In this run, the Temurin JRE container was about 6.5 times the native image size. Native listener readiness and idle memory did **not** meet the original targets. These numbers should not be presented as proof that KURL is a sub-20-ms or sub-35-MB service.

The results are a useful reminder that one benchmark run is not a universal ranking: native had higher throughput at 100 connections, while the JVM result was higher at 1,000; latency percentiles varied by load level. The purpose is to expose the full measured profile, not to claim that either runtime wins every workload.

## What the experiment has shown so far

Kotlin can produce a standalone native Ktor service with a compact distroless image, SQLite storage, and a tested shutdown path. It also brings real costs: native metadata and JNI resources need deliberate handling, and the first measured startup and memory figures miss the ambitious goals.

The useful result is not “native is always smaller.” It is that a small service can be built and tested this way, while its actual resource profile remains visible and reproducible. The same-runner comparison shows a smaller container and lower startup time and RSS for native in this run, but the original native targets remain unmet. The next step is to measure the deployed VPS separately.

**Repository:** https://github.com/toptoppy/kurl
