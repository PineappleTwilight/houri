package eu.kanade.tachiyomi.data.track.mangabaka.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MangaBakaOAuth(
    @SerialName("access_token")
    val accessToken: String,
    @SerialName("refresh_token")
    val refreshToken: String,
    @SerialName("expires_in")
    val expiresIn: Long = 3600,
    @SerialName("expires_at")
    val expiresAt: Long? = null,
    @SerialName("token_type")
    val tokenType: String = "Bearer",
    val scope: String = "",
) {
    private fun effectiveExpiresAt(): Long = expiresAt ?: (System.currentTimeMillis() / 1000 + expiresIn)
    fun isExpired(): Boolean = (System.currentTimeMillis() / 1000) > (effectiveExpiresAt() - 60)
}
