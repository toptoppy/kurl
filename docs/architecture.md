# Architecture and operational risks

## Request path

Ktor/Netty handles HTTP and JSON. Routes validate input and call a small repository using prepared JDBC statements. SQLite stores URL mappings and click counts. The database uses WAL, `synchronous=NORMAL`, a 5-second busy timeout, foreign keys, and a bounded page cache.

Each repository operation opens and closes its own SQLite connection. Writes are synchronized within one process. SQLite's busy timeout is a second line of defense; it is not a multi-process write coordinator.

## Threat model

KURL stores user-supplied redirect targets. It does not fetch those URLs, but the destination is later opened by a user's browser. Creation-time URL checks reject invalid schemes, malformed URLs, service-self-links, localhost names, and resolved private, loopback, link-local, multicast, or carrier-grade NAT addresses. A configured API key limits who can create links and read statistics.

### URL safety

DNS checks happen when the link is created. DNS records can change after validation, and a client's network may resolve the same name differently. Since KURL does not fetch the target, this is not an SSRF boundary for server-side network requests; it is defense in depth against unsafe or confusing links. Deployments with stricter requirements should use an explicit destination allowlist or another policy suited to the users' browser threat model.

The app must also be configured with its public `BASE_URL` so self-referential short links can be rejected.

## Shutdown and health

The process registers a shutdown hook and stops the Ktor server before closing repository resources. SQLite connections are operation-scoped and closed after each query. `/health` checks the database with a lightweight query.

## Native image

GraalVM Native Image may need reachability metadata for libraries that use reflection or JNI. Ktor's current Netty metadata enables shared-arena support, which requires a GraalVM 25 native-image toolchain. The application still compiles to and runs against Java 21; CI keeps Gradle on Temurin 21 while invoking the GraalVM 25 native-image executable. This native build includes only SQLite's Linux x86_64 JNI library to avoid bundling unused platform binaries; create separate native images for other operating systems or CPU architectures. JVM tests alone are insufficient evidence.

## Performance claims

The project has targets for startup under 20 ms, idle RSS under 35 MB, and container size under 60 MB. Do not publish these as achieved values until measured on a named environment with repeatable commands. See [benchmarks.md](benchmarks.md).
