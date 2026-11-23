package top.kananek.kurl

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

class UrlRepository(databasePath: String) : AutoCloseable {
    private val path = Path.of(databasePath).toAbsolutePath()
    private val writeLock = Any()

    init {
        Files.createDirectories(path.parent)
        Class.forName("org.sqlite.JDBC")
        connection().use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA journal_mode = WAL")
            }
        }
        autoMigrate()
    }

    private fun connection(): Connection = DriverManager.getConnection("jdbc:sqlite:$path").also { connection ->
        connection.createStatement().use { statement ->
            statement.execute("PRAGMA synchronous = NORMAL")
            statement.execute("PRAGMA busy_timeout = 5000")
            statement.execute("PRAGMA foreign_keys = ON")
            statement.execute("PRAGMA cache_size = -2000")
        }
    }

    fun autoMigrate() {
        connection().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS urls (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        slug TEXT NOT NULL UNIQUE,
                        original_url TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        click_count INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent(),
                )
                statement.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS idx_urls_slug ON urls(slug)")
            }
        }
    }

    fun save(slug: String, originalUrl: String): UrlRecord = synchronized(writeLock) {
        val createdAt = System.currentTimeMillis() / 1000
        connection().use { connection ->
            connection.prepareStatement(
                "INSERT INTO urls(slug, original_url, created_at) VALUES (?, ?, ?)",
            ).use { statement ->
                statement.setString(1, slug)
                statement.setString(2, originalUrl)
                statement.setLong(3, createdAt)
                statement.executeUpdate()
            }
        }
        UrlRecord(slug, originalUrl, createdAt, 0)
    }

    fun findBySlug(slug: String): UrlRecord? = connection().use { connection ->
        connection.prepareStatement(
            "SELECT slug, original_url, created_at, click_count FROM urls WHERE slug = ?",
        ).use { statement ->
            statement.setString(1, slug)
            statement.executeQuery().use { result ->
                if (!result.next()) null else UrlRecord(
                    slug = result.getString("slug"),
                    originalUrl = result.getString("original_url"),
                    createdAt = result.getLong("created_at"),
                    clickCount = result.getLong("click_count"),
                )
            }
        }
    }

    fun incrementClicks(slug: String): Boolean = synchronized(writeLock) {
        connection().use { connection ->
            connection.prepareStatement("UPDATE urls SET click_count = click_count + 1 WHERE slug = ?").use {
                it.setString(1, slug)
                it.executeUpdate() == 1
            }
        }
    }

    fun checkHealth(): Boolean = connection().use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT 1").use { it.next() }
        }
    }

    override fun close() = Unit
}
