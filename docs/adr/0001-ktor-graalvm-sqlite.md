# ADR 0001: Kotlin, Ktor, GraalVM Native Image, and SQLite

- **Status:** Accepted for the experiment
- **Date:** 2026-11-23

## Context

KURL is an experimental URL shortener with a low-memory and fast-startup goal. The project compares a Kotlin service compiled ahead of time with more common JVM and Go deployments. It needs a small HTTP API, durable storage, and a deployment model that can run as a single binary.

## Decision

- Use Kotlin/JVM and Ktor with Netty for HTTP.
- Use direct SQLite JDBC with prepared statements; do not add an ORM.
- Enable SQLite WAL and a bounded busy timeout, and serialize write operations in the repository.
- Build an optional standalone executable with GraalVM Native Image.
- Keep the source of truth as a normal Gradle/JVM application so local development and tests do not require native compilation.

## Consequences

- JDBC keeps the data-access path explicit and avoids ORM mapping overhead.
- SQLite is simple to deploy but writes remain serialized by SQLite itself; workload measurements are needed before claiming high write throughput.
- Native compilation may require reachability metadata and special handling for the SQLite driver's JNI library. A successful JVM test suite does not prove native compatibility.
- Startup, memory, latency, and image size are measured results, not guarantees.

## Alternatives considered

- Go: likely simpler native deployment, but does not answer the Kotlin experiment.
- JVM-only Kotlin: the simplest runtime and build, but does not test standalone native deployment.
- Reflection-heavy ORM: less explicit storage code, but unnecessary for this small schema and contrary to the low-overhead goal.
