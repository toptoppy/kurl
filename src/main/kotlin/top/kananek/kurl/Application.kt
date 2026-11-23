package top.kananek.kurl

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import java.sql.SQLException

fun main(args: Array<String>) {
    if (args.firstOrNull() == "healthcheck") {
        val port = (System.getenv("PORT") ?: "8080").toInt()
        val connection = java.net.URI("http://127.0.0.1:$port/health").toURL().openConnection()
            as java.net.HttpURLConnection
        connection.connectTimeout = 1_000
        connection.readTimeout = 1_000
        connection.requestMethod = "GET"
        val healthy = runCatching { connection.responseCode == 200 }.getOrDefault(false)
        connection.disconnect()
        if (!healthy) throw IllegalStateException("KURL health check failed")
        return
    }
    val repository = UrlRepository(System.getenv("DATABASE_PATH") ?: "./data/kurl.db")
    val server = embeddedServer(
        Netty,
        host = "0.0.0.0",
        port = (System.getenv("PORT") ?: "8080").toInt(),
        module = { kurlModule(repository) },
    )
    Runtime.getRuntime().addShutdownHook(Thread {
        server.stop(gracePeriodMillis = 1_000, timeoutMillis = 5_000)
        repository.close()
    })
    server.start(wait = true)
}

fun Application.kurlModule(
    repository: UrlRepository,
    baseUrl: String = System.getenv("BASE_URL") ?: "http://localhost:8080",
    apiKey: String? = System.getenv("API_KEY")?.takeIf(String::isNotBlank),
    validator: UrlValidator = UrlValidator(baseUrl),
) {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }

    routing {
        get("/health") {
            val healthy = runCatching { withContext(Dispatchers.IO) { repository.checkHealth() } }.getOrDefault(false)
            call.respond(
                if (healthy) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
                HealthResponse(if (healthy) "UP" else "DOWN", if (healthy) "OK" else "ERROR"),
            )
        }

        post("/api/shorten") {
            if (apiKey != null && call.request.headers["X-Api-Key"] != apiKey) {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Unauthorized"))
                return@post
            }
            val request = runCatching { call.receive<ShortenRequest>() }.getOrNull()
            if (request == null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid JSON request"))
                return@post
            }
            val url = request.url.trim()
            val validationError = withContext(Dispatchers.IO) { validator.validate(url) }
            if (validationError != null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(validationError))
                return@post
            }
            val customSlug = request.custom_slug
            if (customSlug != null && !SLUG_REGEX.matches(customSlug)) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Custom slug must be 3-30 letters, digits, _ or -"))
                return@post
            }
            if (customSlug in RESERVED_SLUGS) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("That slug is reserved"))
                return@post
            }

            val record = if (customSlug != null) {
                try {
                    withContext(Dispatchers.IO) { repository.save(customSlug, url) }
                } catch (error: SQLException) {
                    if (!error.isUniqueConstraintViolation()) throw error
                    call.respond(HttpStatusCode.Conflict, ErrorResponse("Slug is already taken"))
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
                    call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("Could not allocate a unique slug"))
                    return@post
                }
            }
            call.respond(
                HttpStatusCode.Created,
                ShortenResponse(record.slug, "${baseUrl.trimEnd('/')}/${record.slug}", record.originalUrl),
            )
        }

        get("/api/{slug}/stats") {
            if (apiKey != null && call.request.headers["X-Api-Key"] != apiKey) {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Unauthorized"))
                return@get
            }
            val slug = call.parameters["slug"].orEmpty()
            val record = withContext(Dispatchers.IO) { repository.findBySlug(slug) }
            if (record == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Slug not found"))
            } else {
                call.respond(
                    StatsResponse(record.slug, record.originalUrl, record.clickCount, record.createdAt),
                )
            }
        }

        get("/{slug}") {
            val slug = call.parameters["slug"].orEmpty()
            if (slug in RESERVED_SLUGS) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Slug not found"))
                return@get
            }
            val record = withContext(Dispatchers.IO) { repository.findBySlug(slug) }
            if (record == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Slug not found"))
            } else {
                withContext(Dispatchers.IO) { repository.incrementClicks(slug) }
                call.response.headers.append(HttpHeaders.Location, record.originalUrl)
                call.respond(HttpStatusCode.Found)
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
