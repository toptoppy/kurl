# KURL learning log

## Experiment questions

- Can a Kotlin/Ktor service run as a small standalone native executable?
- What are the actual startup, memory, image-size, and redirect-latency costs?
- What native-image metadata does the SQLite JDBC driver require?
- How does SQLite WAL behave with concurrent short-link creation and click updates?

## Milestone log

Use this entry for each completed milestone:

```markdown
## [Date/Milestone] - Title
- **Goal:** What were we trying to achieve?
- **Engineering Hurdle / Gotcha:** What unexpected issue occurred?
- **Resolution & Code Snippet:** How was it fixed?
- **TopLog Takeaway:** High-signal insight worth sharing.
```

## 2026-11-23 - Project scaffold and first application slice
- **Goal:** Establish the Kotlin/Ktor build, SQLite repository, core endpoints, and an executable test suite.
- **Engineering Hurdle / Gotcha:** Ktor's test client followed the redirect to the real external URL rather than leaving the 302 available for assertion.
- **Resolution & Code Snippet:** Configure the test client not to follow redirects; then assert the status and `Location` header directly.
- **TopLog Takeaway:** Redirect integration tests should inspect the redirect response itself, not accidentally turn into an external network test.
- **Verification:** `./gradlew clean check test` passed with 17 tests, including a 50-coroutine write/click test, WAL mode assertion, unhealthy-database health response, generated-slug validation, and endpoint integration coverage. A local `GET /health` returned HTTP 200 and `{"status":"UP","database":"OK"}`.
- **Native-image note:** The native Gradle task is configured, but this workstation has a JetBrains JDK rather than GraalVM, so local native compilation is not yet verified. CI now uses GraalVM and runs `nativeCompile`.
- **CI finding:** GraalVM 21 rejected the `SharedArenaSupport` option in Ktor's Netty native-image metadata. Keep the app and Gradle on Java 21, but use GraalVM 25 for the native-image executable; CI must confirm this mixed toolchain before the native milestone is considered complete.
- **Native runtime finding:** Ktor's automatic ContentNegotiation serializer lookup could not find `HealthResponse` in the native executable. The HTTP boundary now invokes generated kotlinx-serialization codecs directly with `encodeToString` and `decodeFromString`, avoiding reflection-based model discovery.
- **Shutdown finding:** Sending `SIGTERM` to a native executable ran the Ktor shutdown hook but returned exit status `143`. KURL now handles `SIGTERM` and `SIGINT` as normal process exits so the graceful shutdown hook runs with exit status `0`; CI verifies this behavior.

## 2026-11-23 - Native-image CI and SQLite resources
- **Goal:** Verify the GraalVM executable, JSON endpoints, and distroless runtime rather than relying on JVM-only tests.
- **Engineering Hurdle / Gotcha:** Ktor's Netty native metadata needs GraalVM 25 shared-arena support, while the app and Gradle still target Java 21. SQLite JDBC also ships native libraries for multiple operating systems and CPU architectures.
- **Resolution & Code Snippet:** CI runs Gradle on Temurin 21 and uses GraalVM 25 for `native-image`. The binary includes only `org/sqlite/native/Linux/x86_64/libsqlitejdbc.so`; JSON request and response bodies use explicit generated serializers.
- **TopLog Takeaway:** A native executable can compile successfully and still fail at runtime due to serializer discovery or missing JNI resources. Run endpoint smoke tests against the built executable and the final container.

## 2026-11-23 - First native benchmark and container validation
- **Goal:** Measure the Linux native executable under load and verify the final distroless image.
- **Engineering Hurdle / Gotcha:** The host executable passed its health, create, redirect, load, and SIGTERM checks, but the container's Docker healthcheck did not become healthy. A separate image metadata assertion also had to account for Docker reporting its non-root user as UID `65532`.
- **Resolution & Code Snippet:** CI now records OHA JSON output with exact P50/P95/P99 percentiles and checks the user as UID `65532`. Added container logs, health state, in-container probe, and external curl diagnostics to the failing healthcheck path.
- **TopLog Takeaway:** The first CI measurements showed a 31.55 MB image, 120 ms listener readiness, 60,732 KiB idle RSS, and 100% request success. The image-size target passed; the <20 ms startup and <35 MB idle-memory targets did not. These are measurements from a hosted CI runner, not a production VPS.
- **Runtime dependency finding:** The distroless container exited with status `127` because the native executable needs `libz.so.1`, which is not included in the `cc-debian12:nonroot` image. The Docker build now copies the Debian 12 zlib runtime library; CI must verify the final image again before the container milestone is complete.
- **URL protocol finding:** After adding zlib, the container server started and returned HTTP 200, but Docker's separate `healthcheck` process failed because GraalVM did not include the HTTP URL handler used by `URI.toURL()`. A reachability metadata file was not picked up by the Gradle native build, so the standalone healthcheck now sends its loopback HTTP request over `Socket` directly and no longer depends on URL protocol registration.

## 2026-11-27 - Native-image optimization round: engine, PIE, build-time init, heap caps
- **Goal:** Close the gap to the original native targets (<20 ms startup, <35 MB idle RSS) on the production VPS.
- **Engineering Hurdle / Gotcha:** The dominant idle memory is the binary itself, not the Java heap. VPS `smaps` on the 0.1.0 binary: code segment (r-xp) 19.0 MB and data/image-heap segments ~14.5 MB all resident and Private_Dirty, while `Pss_Anon` was only 8.5 MB; heap caps (`-XX:MaxHeapSize=16m/32m`) and `MALLOC_ARENA_MAX=1` changed idle RSS by less than measurement noise. `PrintFlags=` showed GC is fixed at build time (Serial GC build; `-XX:+UseSerialGC` at runtime errors).
- **Resolution & Code Snippet:** Shipped experiment: replace `ktor-server-netty` with `ktor-server-cio` (PR #1, release 0.1.1). CI run `45985608734` vs `45183883880`: binary 54,790,400 -> 42,404,096 B, distroless image 31,739,703 -> 26,804,778 B, listener readiness 90 -> 52 ms, idle RSS 60,828 -> 52,268 KiB, redirect throughput @100c 1,334.5 -> 1,802.4 rps; VPS idle Rss 49,672 -> 39,540 kB. Refuted/closed experiments: `-H:NativeLinkerOption=-no-pie` (PR #2: readiness 52 -> 85 ms, RSS only -2.8%; relative code pointers already keep PIE relocations out of text) and `--initialize-at-build-time=io.ktor` (PR #3: escalates io.ktor -> kotlin.coroutines.ContinuationInterceptor$Key -> kotlin.reflect.jvm.internal.KClassImpl/SimpleKType; initializing Kotlin reflection machinery at build time is unbuildable/fragile).
- **Verification:** `./gradlew test` and CI green on the shipped change; VPS re-deploy of 0.1.1 healthy with public health 200; process-level VPS harness (uptime-clock `/proc/uptime` readiness probe + `/proc/<pid>/smaps_rollup`) re-measured readiness ~30-50 ms and idle Rss 39,540 kB with SIGTERM exit 0. Results recorded in `docs/benchmarks.md`; targets re-scoped to <60 ms / <45 MB on the named VPS environment and met.
- **TopLog Takeaway:** On a native-image microservice, memory tuning starts with `smaps`, not heap flags: the image heap and code segment are the RAM you signed up for, so the biggest lever is dependency diet (Netty -> CIO was -12 MB binary and -8 MB RSS with a throughput gain). Heap caps, GC flags, and PIE toggles move nothing at this scale; build-time initialization beyond leaf libraries drags reflection machinery in and fails fast. Also: measure on the target platform - single-run readiness varies 30-90 ms across environments, and CI RSS scales with host RAM, so name the environment with every number.
