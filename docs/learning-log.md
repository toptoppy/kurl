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
- **Verification:** `./gradlew clean check test` passed with 16 tests, including a 50-coroutine write/click test, an assertion that the database uses WAL, and an unhealthy-database health response. A local `GET /health` returned HTTP 200 and `{"status":"UP","database":"OK"}`.
- **Native-image note:** The native Gradle task is configured, but this workstation has a JetBrains JDK rather than GraalVM, so local native compilation is not yet verified. CI now uses GraalVM and runs `nativeCompile`.
- **CI finding:** GraalVM 21 rejected the `SharedArenaSupport` option in Ktor's Netty native-image metadata. Keep the app and Gradle on Java 21, but use GraalVM 25 for the native-image executable; CI must confirm this mixed toolchain before the native milestone is considered complete.
- **Native runtime finding:** Ktor's automatic ContentNegotiation serializer lookup could not find `HealthResponse` in the native executable. The HTTP boundary now invokes generated kotlinx-serialization codecs directly with `encodeToString` and `decodeFromString`, avoiding reflection-based model discovery.
