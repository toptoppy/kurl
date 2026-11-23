package top.kananek.kurl

import kotlinx.serialization.Serializable

@Serializable
data class ShortenRequest(val url: String, val custom_slug: String? = null)

@Serializable
data class ShortenResponse(val slug: String, val short_url: String, val original_url: String)

@Serializable
data class StatsResponse(
    val slug: String,
    val original_url: String,
    val click_count: Long,
    val created_at: Long,
)

@Serializable
data class HealthResponse(val status: String, val database: String)

@Serializable
data class ErrorResponse(val error: String)

data class UrlRecord(
    val slug: String,
    val originalUrl: String,
    val createdAt: Long,
    val clickCount: Long,
)
