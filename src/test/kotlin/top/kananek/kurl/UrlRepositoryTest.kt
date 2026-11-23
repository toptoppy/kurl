package top.kananek.kurl

import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UrlRepositoryTest {
    private fun repository() = UrlRepository(Files.createTempDirectory("kurl-test").resolve("db.sqlite").toString())

    @Test
    fun `saves and retrieves a URL`() {
        repository().use { repo ->
            val saved = repo.save("abc123", "https://example.org/path")
            val found = assertNotNull(repo.findBySlug("abc123"))
            assertEquals(saved.originalUrl, found.originalUrl)
            assertEquals(0, found.clickCount)
        }
    }

    @Test
    fun `duplicate slug is rejected`() {
        repository().use { repo ->
            repo.save("abc123", "https://example.org")
            assertFalse(runCatching { repo.save("abc123", "https://other.example") }.isSuccess)
        }
    }

    @Test
    fun `increments click counter only for existing slugs`() {
        repository().use { repo ->
            repo.save("abc123", "https://example.org")
            assertTrue(repo.incrementClicks("abc123"))
            assertEquals(1, repo.findBySlug("abc123")?.clickCount)
            assertFalse(repo.incrementClicks("missing"))
            assertNull(repo.findBySlug("missing"))
        }
    }

    @Test
    fun `database health query succeeds`() {
        repository().use { assertTrue(it.checkHealth()) }
    }

    @Test
    fun `database uses write ahead logging`() {
        repository().use { assertEquals("wal", it.journalMode()) }
    }

    @Test
    fun `concurrent inserts and click increments complete without lost updates`() = runBlocking {
        repository().use { repo ->
            repo.save("shared", "https://example.org")
            (1..50).map {
                async(Dispatchers.IO) {
                    repo.save("link$it", "https://example.org/$it")
                    assertTrue(repo.incrementClicks("shared"))
                }
            }.awaitAll()
            assertEquals(50, repo.findBySlug("shared")?.clickCount)
        }
    }
}
