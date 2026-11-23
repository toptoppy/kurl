package top.kananek.kurl

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.util.Locale

class UrlValidator(
    private val baseUrl: String,
    private val resolve: (String) -> Array<InetAddress> = InetAddress::getAllByName,
) {
    fun validate(value: String): String? {
        if (value.length > MAX_URL_LENGTH) return "URL must be 2048 characters or fewer"
        val uri = runCatching { URI(value.trim()) }.getOrNull()
            ?: return "URL is invalid"
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) {
            return "URL must be an absolute HTTP or HTTPS URL"
        }

        val host = uri.host.removePrefix("[").removeSuffix("]").trimEnd('.').lowercase(Locale.ROOT)
        val ownHost = runCatching { URI(baseUrl).host?.trimEnd('.')?.lowercase(Locale.ROOT) }.getOrNull()
        if (ownHost != null && host == ownHost) return "URL points to this service"
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) {
            return "Private and local network destinations are not allowed"
        }

        val addresses = runCatching { resolve(host) }.getOrElse {
            return "URL host could not be resolved"
        }
        if (addresses.isEmpty() || addresses.any(::isForbiddenAddress)) {
            return "Private and local network destinations are not allowed"
        }
        return null
    }

    private fun isForbiddenAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress
        ) return true

        val bytes = address.address
        if (address is Inet4Address) {
            val first = bytes[0].toInt() and 0xff
            val second = bytes[1].toInt() and 0xff
            return first == 100 && second in 64..127
        }
        if (address is Inet6Address) {
            val first = bytes[0].toInt() and 0xff
            return first and 0xfe == 0xfc // Unique-local fc00::/7
        }
        return false
    }

    companion object {
        const val MAX_URL_LENGTH = 2048
    }
}
