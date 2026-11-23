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
- **Verification:** `./gradlew clean check test` passed with 15 tests, including a 50-coroutine write/click test and an assertion that the database uses WAL. A local `GET /health` returned HTTP 200 and `{"status":"UP","database":"OK"}`.
- **Native-image note:** The native Gradle task is configured, but this workstation has a JetBrains JDK rather than GraalVM, so local native compilation is not yet verified. CI now uses GraalVM and runs `nativeCompile`.
- **CI finding:** The first GraalVM 21 CI build rejected a `SharedArenaSupport` option shipped in Ktor 3.6.1's Netty native-image metadata. The project now uses Ktor 3.4.3 to preserve the Java 21/GraalVM 21 target; CI must confirm this compatibility before the native milestone is considered complete.
