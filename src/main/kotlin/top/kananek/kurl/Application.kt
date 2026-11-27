package top.kananek.kurl

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.security.SecureRandom
import java.sql.SQLException
import sun.misc.Signal
import sun.misc.SignalHandler
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    if (args.firstOrNull() == "healthcheck") {
        val port = (System.getenv("PORT") ?: "8080").toInt()
        val healthy = runCatching {
            java.net.Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress("127.0.0.1", port), 1_000)
                socket.soTimeout = 1_000
                socket.getOutputStream().write(
                    "GET /health HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n"
                        .toByteArray(Charsets.US_ASCII),
                )
                socket.getInputStream().bufferedReader(Charsets.US_ASCII).use { reader ->
                    reader.readLine()?.let { it.startsWith("HTTP/1.1 200") || it.startsWith("HTTP/1.0 200") }
                        ?: false
                }
            }
        }.getOrDefault(false)
        if (!healthy) throw IllegalStateException("KURL health check failed")
        return
    }
    val repository = UrlRepository(System.getenv("DATABASE_PATH") ?: "./data/kurl.db")
    val server = embeddedServer(
        CIO,
        host = "0.0.0.0",
        port = (System.getenv("PORT") ?: "8080").toInt(),
        module = { kurlModule(repository) },
    )
    Runtime.getRuntime().addShutdownHook(Thread {
        server.stop(gracePeriodMillis = 1_000, timeoutMillis = 5_000)
        repository.close()
    })
    Signal.handle(Signal("TERM"), SignalHandler { exitProcess(0) })
    Signal.handle(Signal("INT"), SignalHandler { exitProcess(0) })
    server.start(wait = true)
}

fun Application.kurlModule(
    repository: UrlRepository,
    baseUrl: String = System.getenv("BASE_URL") ?: "http://localhost:8080",
    apiKey: String? = System.getenv("API_KEY")?.takeIf(String::isNotBlank),
    validator: UrlValidator = UrlValidator(baseUrl),
) {
    routing {
        get("/health") {
            val healthy = runCatching { withContext(Dispatchers.IO) { repository.checkHealth() } }.getOrDefault(false)
            call.respondJson(
                HealthResponse(if (healthy) "UP" else "DOWN", if (healthy) "OK" else "ERROR"),
                if (healthy) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
            )
        }

        post("/api/shorten") {
            if (apiKey != null && call.request.headers["X-Api-Key"] != apiKey) {
                call.respondJson(ErrorResponse("Unauthorized"), HttpStatusCode.Unauthorized)
                return@post
            }
            val request = runCatching {
                kurlJson.decodeFromString<ShortenRequest>(call.receiveText())
            }.getOrNull()
            if (request == null) {
                call.respondJson(ErrorResponse("Invalid JSON request"), HttpStatusCode.BadRequest)
                return@post
            }
            val url = request.url.trim()
            val validationError = withContext(Dispatchers.IO) { validator.validate(url) }
            if (validationError != null) {
                call.respondJson(ErrorResponse(validationError), HttpStatusCode.BadRequest)
                return@post
            }
            val customSlug = request.custom_slug
            if (customSlug != null && !SLUG_REGEX.matches(customSlug)) {
                call.respondJson(
                    ErrorResponse("Custom slug must be 3-30 letters, digits, _ or -"),
                    HttpStatusCode.BadRequest,
                )
                return@post
            }
            if (customSlug in RESERVED_SLUGS) {
                call.respondJson(ErrorResponse("That slug is reserved"), HttpStatusCode.BadRequest)
                return@post
            }

            val record = if (customSlug != null) {
                try {
                    withContext(Dispatchers.IO) { repository.save(customSlug, url) }
                } catch (error: SQLException) {
                    if (!error.isUniqueConstraintViolation()) throw error
                    call.respondJson(ErrorResponse("Slug is already taken"), HttpStatusCode.Conflict)
                    return@post
                }
            } else {
                var saved: UrlRecord? = null
                withContext(Dispatchers.IO) {
                    repeat(5) {
                        if (saved == null) {
                            saved = try {
                                repository.save(generateSlug(), url)
                            } catch (error: SQLException) {
                                if (!error.isUniqueConstraintViolation()) throw error
                                null
                            }
                        }
                    }
                }
                saved ?: run {
                    call.respondJson(
                        ErrorResponse("Could not allocate a unique slug"),
                        HttpStatusCode.ServiceUnavailable,
                    )
                    return@post
                }
            }
            call.respondJson(
                ShortenResponse(record.slug, "${baseUrl.trimEnd('/')}/${record.slug}", record.originalUrl),
                HttpStatusCode.Created,
            )
        }

        get("/api/{slug}/stats") {
            if (apiKey != null && call.request.headers["X-Api-Key"] != apiKey) {
                call.respondJson(ErrorResponse("Unauthorized"), HttpStatusCode.Unauthorized)
                return@get
            }
            val slug = call.parameters["slug"].orEmpty()
            val record = withContext(Dispatchers.IO) { repository.findBySlug(slug) }
            if (record == null) {
                call.respondJson(ErrorResponse("Slug not found"), HttpStatusCode.NotFound)
            } else {
                call.respondJson(
                    StatsResponse(record.slug, record.originalUrl, record.clickCount, record.createdAt),
                )
            }
        }

        get("/{slug}") {
            val slug = call.parameters["slug"].orEmpty()
            if (slug in RESERVED_SLUGS) {
                call.respondJson(ErrorResponse("Slug not found"), HttpStatusCode.NotFound)
                return@get
            }
            val record = withContext(Dispatchers.IO) { repository.findBySlug(slug) }
            if (record == null) {
                call.respondJson(ErrorResponse("Slug not found"), HttpStatusCode.NotFound)
            } else {
                withContext(Dispatchers.IO) { repository.incrementClicks(slug) }
                call.response.headers.append(HttpHeaders.Location, record.originalUrl)
                call.respondText("", ContentType.Text.Plain, HttpStatusCode.Found)
            }
        }
    }
}

private val SLUG_REGEX = Regex("^[a-zA-Z0-9_-]{3,30}$")
private val RESERVED_SLUGS = setOf("api", "health", "robots.txt", "favicon.ico")
private val slugRandom = SecureRandom()
private const val BASE62 = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"

private fun generateSlug(): String = buildString(6) {
    repeat(6) { append(BASE62[slugRandom.nextInt(BASE62.length)]) }
}

private fun SQLException.isUniqueConstraintViolation(): Boolean = errorCode and 0xff == 19

private val kurlJson = Json { ignoreUnknownKeys = true }

private suspend inline fun <reified T> ApplicationCall.respondJson(
    body: T,
    status: HttpStatusCode = HttpStatusCode.OK,
) {
    respondText(kurlJson.encodeToString(body), ContentType.Application.Json, status)
}
