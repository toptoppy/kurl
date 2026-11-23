package top.kananek.kurl

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import java.net.InetAddress
import java.nio.file.Files

class ApplicationTest {
    private fun testRepo() =
        UrlRepository(kotlin.io.path.createTempDirectory("kurl-http-test").resolve("db.sqlite").toString())
    private val publicResolver: (String) -> Array<InetAddress> = {
        arrayOf(InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8)))
    }

    @Test
    fun `health endpoint reports application and database healthy`() = testApplication {
        val repository = testRepo()
        application { kurlModule(repository) }
        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"status\":\"UP\""))
        repository.close()
    }

    @Test
    fun `health endpoint reports unavailable when the database path fails`() = testApplication {
        val directory = Files.createTempDirectory("kurl-health-test").resolve("data")
        val repository = UrlRepository(directory.resolve("db.sqlite").toString())
        Files.list(directory).use { paths -> paths.forEach(Files::delete) }
        Files.delete(directory)
        Files.writeString(directory, "not a directory")
        application { kurlModule(repository) }
        assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/health").status)
    }

    @Test
    fun `shorten create redirect and stats work end to end`() = testApplication {
        val repository = testRepo()
        application {
            kurlModule(repository, "https://s.example.test", validator = UrlValidator("https://s.example.test", publicResolver))
        }

        val created = client.post("/api/shorten") {
            header(HttpHeaders.ContentType, "application/json")
            setBody("""{"url":"https://example.org/path","custom_slug":"my-link"}""")
        }
        assertEquals(HttpStatusCode.Created, created.status)
        assertTrue(created.bodyAsText().contains("my-link"))

        val duplicate = client.post("/api/shorten") {
            header(HttpHeaders.ContentType, "application/json")
            setBody("""{"url":"https://example.org/another","custom_slug":"my-link"}""")
        }
        assertEquals(HttpStatusCode.Conflict, duplicate.status)

        val redirect = client.config { followRedirects = false }.get("/my-link")
        assertEquals(HttpStatusCode.Found, redirect.status)
        assertEquals("https://example.org/path", redirect.headers[HttpHeaders.Location])

        val stats = client.get("/api/my-link/stats")
        assertEquals(HttpStatusCode.OK, stats.status)
        val parsed = Json.decodeFromString<StatsResponse>(stats.bodyAsText())
        assertEquals(1, parsed.click_count)
        repository.close()
    }

    @Test
    fun `invalid and private URLs are rejected`() = testApplication {
        val repository = testRepo()
        val privateResolver: (String) -> Array<InetAddress> = {
            arrayOf(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
        }
        application {
            kurlModule(repository, validator = UrlValidator("http://localhost:8080", privateResolver))
        }
        for (url in listOf("not-a-url", "http://127.0.0.1/admin", "http://10.0.0.5/")) {
            val response = client.post("/api/shorten") {
                header(HttpHeaders.ContentType, "application/json")
                setBody("""{"url":"$url"}""")
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
        repository.close()
    }

    @Test
    fun `configured API key protects create and stats`() = testApplication {
        val repository = testRepo()
        application {
            kurlModule(repository, apiKey = "secret", validator = UrlValidator("http://localhost", publicResolver))
        }
        val unauthorized = client.post("/api/shorten") {
            header(HttpHeaders.ContentType, "application/json")
            setBody("""{"url":"https://example.org","custom_slug":"my-link"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, unauthorized.status)
        repository.save("my-link", "https://example.org")
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/my-link/stats").status)
        val authorized = client.get("/api/my-link/stats") { header("X-Api-Key", "secret") }
        assertEquals(HttpStatusCode.OK, authorized.status)
        repository.close()
    }

    @Test
    fun `unknown slug returns not found`() = testApplication {
        val repository = testRepo()
        application { kurlModule(repository) }
        assertEquals(HttpStatusCode.NotFound, client.get("/not-found").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/not-found/stats").status)
        assertNotNull(repository)
        repository.close()
    }

    @Test
    fun `missing custom slug generates a six character base62 slug`() = testApplication {
        val repository = testRepo()
        application {
            kurlModule(repository, validator = UrlValidator("http://localhost:8080", publicResolver))
        }
        val response = client.post("/api/shorten") {
            header(HttpHeaders.ContentType, "application/json")
            setBody("""{"url":"https://example.org/automatic"}""")
        }
        assertEquals(HttpStatusCode.Created, response.status)
        val body = Json.decodeFromString<ShortenResponse>(response.bodyAsText())
        assertTrue(Regex("^[0-9a-zA-Z]{6}$").matches(body.slug))
        repository.close()
    }
}
