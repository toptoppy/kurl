package top.kananek.kurl

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UrlValidatorTest {
    private fun address(vararg octets: Int) =
        InetAddress.getByAddress(octets.map(Int::toByte).toByteArray())

    @Test
    fun `accepts public HTTP and HTTPS hosts`() {
        val validator = UrlValidator("https://s.example.test") { arrayOf(address(8, 8, 8, 8)) }
        assertNull(validator.validate("https://example.org/path"))
        assertNull(validator.validate("http://example.org"))
    }

    @Test
    fun `rejects unsupported schemes malformed URLs and oversize values`() {
        val validator = UrlValidator("https://s.example.test") { arrayOf(address(8, 8, 8, 8)) }
        assertEquals("URL must be an absolute HTTP or HTTPS URL", validator.validate("file:///etc/passwd"))
        assertEquals("URL must be an absolute HTTP or HTTPS URL", validator.validate("not-a-url"))
        assertEquals(
            "URL must be 2048 characters or fewer",
            validator.validate("https://example.org/" + "x".repeat(UrlValidator.MAX_URL_LENGTH)),
        )
    }

    @Test
    fun `rejects service self-links and private resolved addresses`() {
        val self = UrlValidator("https://s.example.test") { arrayOf(address(8, 8, 8, 8)) }
        assertEquals("URL points to this service", self.validate("https://s.example.test/anything"))
        val privateAddresses = listOf(
            address(10, 0, 0, 1),
            address(172, 16, 0, 1),
            address(192, 168, 0, 1),
            address(127, 0, 0, 1),
            address(169, 254, 1, 1),
            address(100, 64, 0, 1),
        )
        privateAddresses.forEach { address ->
            val validator = UrlValidator("https://s.example.test") { arrayOf(address) }
            assertEquals(
                "Private and local network destinations are not allowed",
                validator.validate("http://destination.example"),
            )
        }
    }

    @Test
    fun `rejects localhost and resolution failures`() {
        val validator = UrlValidator("https://s.example.test") { throw IllegalStateException("DNS failure") }
        assertEquals(
            "Private and local network destinations are not allowed",
            validator.validate("http://localhost/admin"),
        )
        assertEquals("URL host could not be resolved", validator.validate("https://missing.example"))
    }
}
