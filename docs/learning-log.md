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
- **URL protocol finding:** After adding zlib, the container server started and returned HTTP 200, but Docker's separate `healthcheck` process failed because GraalVM did not include the HTTP URL handler used by `URI.toURL()`. The application now registers the `sun.net.www.protocol.http.Handler` constructor in `META-INF/native-image/reachability-metadata.json`; this uses current reachability metadata instead of the deprecated `--enable-url-protocols` option.
