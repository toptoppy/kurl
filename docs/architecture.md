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

The original experiment targets (startup under 20 ms, idle RSS under 35 MB) were re-scoped on 2026-11-27 after measurement: the Ktor + SQLite native-image stack has a floor of roughly 30-50 ms startup and ~39 MB idle RSS on the production VPS (the resident memory is the binary's own code and image heap, not the Java heap). The re-scoped targets are startup under 60 ms and idle RSS under 45 MB on the named VPS environment; CI results may exceed the RSS target because native heap ergonomics scale with host RAM. Do not publish any of these as achieved values until measured on a named environment with repeatable commands. See [benchmarks.md](benchmarks.md).
