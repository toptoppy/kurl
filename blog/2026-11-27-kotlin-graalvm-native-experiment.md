# How Small Can Kotlin Go? Building KURL, a URL Shortener on GraalVM Native Image

*Published on TopLog · 2026-11-27 · by Kananek Mongkolsawat*

When you need a tiny URL shortener, the reflex is Go or Python. Kotlin rarely enters
the conversation, because the JVM has a reputation: hundreds of megabytes of RAM and
a slow boot, even for a service that just redirects traffic. GraalVM Native Image
promises to change that — ahead-of-time compilation into a single binary with no JVM
at runtime.

This post is an honest field report from building **[KURL](https://github.com/toptoppy/kurl)**,
an experimental self-hosted URL shortener in Kotlin. It currently runs on a Hetzner
VPS at [s.kananek.top](https://s.kananek.top). Some goals were met. Some were not.
All numbers below are measured, reproducible ones.

## The question

Can a Kotlin service reach the operating envelope we actually want for a small
self-hosted utility?

| Goal | Target | Result |
| --- | ---: | --- |
| Distroless container image | < 60 MB | ✅ 31.7 MB |
| Graceful shutdown (SIGTERM → exit 0) | 0 | ✅ 0 |
| Process start → TCP listener | < 20 ms | ❌ 40 ms (VPS) / 90 ms (CI) |
| Idle RSS | < 35 MB | ❌ ~48 MB (Pss) |

Two of four. The misses are interesting, and I'll come back to them.

## The stack

KURL is deliberately boring where it can be:

- **Ktor 3.x** for HTTP (Kotlin-coroutine based)
- **Embedded SQLite** in WAL mode — no external database, one file on a volume
- **Direct JDBC with prepared statements** — no ORM, no reflection on the data path
- **GraalVM Native Image** for the deployable binary; the Gradle app stays the source of truth and runs tests on a normal JVM

```kotlin
// The entire HTTP surface: shorten, redirect, stats, health
routing {
    get("/{slug}") {
        val record = repository.findBySlug(call.parameters["slug"].orEmpty())
            ?: return@get call.respondError(HttpStatusCode.NotFound)
        repository.incrementClicks(record.slug)
        call.response.headers.append(HttpHeaders.Location, record.originalUrl)
        call.respondText("", ContentType.Text.Plain, HttpStatusCode.Found)
    }
}
```

The write path is intentionally simple: SQLite WAL with `synchronous=NORMAL`, a
5-second busy timeout, and writes serialized inside the repository. One process owns
the database file; the busy timeout is a second line of defense, not a multi-process
coordinator. A 50-coroutine concurrent write/click test covers this in CI.

## GraalVM native-image landmines (and the fixes)

The JVM test suite was green long before the native build worked. Everything below
bit us for real during the experiment:

**1. SQLite JNI must be carried into the image.** The SQLite JDBC driver loads a
native library at runtime. Without an explicit include, the binary starts and dies
the first time it touches the database:

```kotlin
buildArgs.addAll(
    "--no-fallback",
    "-H:IncludeResources=org/sqlite/native/Linux/x86_64/libsqlitejdbc.so",
)
```

Shipping only the target-architecture library keeps the binary smaller than the
driver's default all-platform bundle.

**2. Distroless containers don't ship zlib.** `gcr.io/distroless/cc` has glibc but
not `libz.so.1`, and the SQLite loader wants it. The image now copies Debian 12's
zlib runtime library in. Exit code 127 taught us this the hard way.

**3. Reflection-based serializer discovery breaks.** Ktor's automatic content
negotiation could not locate `HealthResponse` inside the native image. The fix was to
stop reflecting: invoke the generated kotlinx.serialization codecs directly at the
HTTP boundary (`encodeToString` / `decodeFromString`).

**4. The healthcheck couldn't build a `URL`.** GraalVM did not include the HTTP URL
handler used by `URI.toURL()`, so the container's Docker healthcheck kept failing
while the server was fine. The binary now has a `healthcheck` subcommand that does a
raw `Socket` loopback request — no URL machinery involved:

```kotlin
Socket().use { socket ->
    socket.connect(InetSocketAddress("127.0.0.1", port), 1_000)
    socket.getOutputStream().write("GET /health HTTP/1.1\r\n...".toByteArray())
    // read the status line, assert "HTTP/1.1 200"
}
```

**5. SIGTERM exited 143.** The shutdown hook ran, but the default signal behavior
still killed the process. Handling `SIGTERM`/`SIGINT` explicitly and exiting 0 makes
container orchestration see a clean stop, and CI asserts it.

**6. The toolchain is split on purpose.** The application targets Java 21, but
Ktor's Netty native metadata needs GraalVM 25's shared-arena support. CI therefore
runs Gradle on Temurin 21 and invokes the GraalVM 25 `native-image` executable.
Local development stays on the JVM — only CI pays the native-compile tax, which also
keeps iteration fast: `./gradlew test` runs in seconds locally while the native
pipeline produces the release image.

**7. One warning worth silencing.** sqlite-jdbc calls `System::load`, which JEP 472
flags at runtime. `--enable-native-access=ALL-UNNAMED` in the build args removes the
noise.

## The numbers

Measurements come from a GitHub Actions Linux x86_64 runner (run `45183883880`,
GraalVM 25.0.4, Temurin 21.0.13, Xeon 6975P-C) and from the production Hetzner VPS
(Debian 12, same binary, measured with `ps`/`smaps` and an uptime-clock readiness
probe). Exact reproduction commands live in
[`docs/benchmarks.md`](https://github.com/toptoppy/kurl/blob/main/docs/benchmarks.md).

### Native vs JVM, same runner

| Metric | Native | Temurin 21 JVM |
| --- | ---: | ---: |
| Process start → TCP listener | 90 ms | 628 ms |
| Idle RSS | 60.8 MB | 128.7 MB |
| Peak RSS under load | 123.8 MB | 320.9 MB |
| Redirect throughput (1,000 connections) | 1,473 rps | 1,522 rps |
| Deployable artifact | 31.7 MB image | 206.2 MB image |

The native image is **6.5× smaller** and uses **half the idle memory** of the JVM
distribution, with redirect throughput statistically in the same league. That alone
would justify it for a fleet of tiny services.

### On the production VPS

| Metric | Measured | Target |
| --- | ---: | ---: |
| Listener readiness | 40.0 ms | < 20 ms |
| Idle memory (Pss) | 48.4 MB | < 35 MB |
| First `SIGTERM` exit | 0 | 0 |

### Where the memory actually goes

Heap caps did nothing to the RSS, so I inspected `smaps` at idle. The breakdown:

- Native code segment (r-xp): ~19 MB — and dirty, because PIE relocations write to
  it during startup
- Read-only data segments (image heap): ~14.5 MB
- Pure anonymous memory (malloc, buffers): ~4.6 MB
- libc and misc: ~2 MB

The Java heap is *not* the problem — `Pss_Anon` was 8.5 MB. The binary itself is the
memory: code plus the image heap of everything reachable. Ktor's Netty engine is the
largest single contributor, which is why the current experiment swaps it for Ktor's
CIO engine (pure coroutines, far less native surface). The CI benchmark on that PR
will decide whether it ships.

### Why 20 ms startup didn't happen

Ktor reports "Application started in 0.003 seconds" — the application layer is 3 ms.
The rest is substrate VM init, image loading, and the SQLite JNI load. That floor is
set by the runtime model, and on this evidence, a 20 ms target is not reachable with
this stack. It *is* reachable for a hand-rolled socket server — but that's a
different project.

## Takeaways

1. **Kotlin can ship Go-sized containers** (31.7 MB distroless image) — but "Go-sized
   memory" needs dependency discipline, not just AOT compilation. The image heap
   contains everything reachable, so every library you pull in is RAM you sign up for.
2. **JVM tests prove nothing about native.** Every native failure we hit — serializer
   discovery, JNI resources, URL handlers, signal handling — was invisible on the JVM.
   Smoke-test the actual binary and the final container in CI.
3. **Fast local iteration is an architecture decision.** Keeping the Gradle/JVM app as
   the source of truth means tests run in seconds and native compilation is a
   CI-only, release-time concern.
4. **Measure on the target platform.** CI numbers and VPS numbers differed
   meaningfully (90 ms vs 40 ms startup). Publish which environment produced which
   number, and keep unmet targets unmet until a repeatable measurement says otherwise.

## Try it

The repository is [github.com/toptoppy/kurl](https://github.com/toptoppy/kurl) (MIT).
Quick start:

```sh
docker run --rm -p 8080:8080 -v kurl-data:/app/data ghcr.io/toptoppy/kurl:latest
```

```sh
curl -X POST http://localhost:8080/api/shorten \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://example.com/a/long/path"}'
```

Set `API_KEY` to require an `X-Api-Key` header for link creation and stats; redirects
and health checks stay public. The deployment compose file in
[`deployment/`](https://github.com/toptoppy/kurl/tree/main/deployment) is what runs
behind Caddy on the VPS today.
