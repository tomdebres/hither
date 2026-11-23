// Adapted from SeerrTV's model/UserModels.kt (MIT, see THIRD-PARTY.md): the tolerant integer
// serializer and the user model, cut down to the fields Hither reads.
package com.tomdebres.hither.core.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * An integer that some Seerr versions and proxies send as a string ("32") or as null. Anything
 * that is not a whole number reads as null rather than failing the whole response.
 */
@OptIn(ExperimentalSerializationApi::class)
object LenientIntSerializer : KSerializer<Int?> {
    override val descriptor: SerialDescriptor = Int.serializer().nullable.descriptor

    override fun serialize(encoder: Encoder, value: Int?) {
        if (value == null) encoder.encodeNull() else encoder.encodeInt(value)
    }

    override fun deserialize(decoder: Decoder): Int? {
        val json = decoder as? JsonDecoder ?: return decoder.decodeInt()
        val element = json.decodeJsonElement()
        if (element !is JsonPrimitive) return null
        return if (element.isString) element.content.toIntOrNull() else element.intOrNull
    }
}

/**
 * A Seerr user, as `GET /auth/me` returns it. The body of `POST /auth/local` has the same shape
 * with most fields missing and `permissions` not to be trusted: ask `auth/me`.
 */
@Serializable
data class User(
    val id: Int,
    val displayName: String = "",
    val email: String? = null,
    val username: String? = null,
    @Serializable(with = LenientIntSerializer::class)
    val permissions: Int? = null,
    @Serializable(with = LenientIntSerializer::class)
    val userType: Int? = null,
    val avatar: String? = null,
    @Serializable(with = LenientIntSerializer::class)
    val requestCount: Int? = null,
    @Serializable(with = LenientIntSerializer::class)
    val movieQuotaLimit: Int? = null,
    @Serializable(with = LenientIntSerializer::class)
    val movieQuotaDays: Int? = null,
    @Serializable(with = LenientIntSerializer::class)
    val tvQuotaLimit: Int? = null,
    @Serializable(with = LenientIntSerializer::class)
    val tvQuotaDays: Int? = null,
    /** The account's own Seerr settings, as `auth/me` carries them. */
    val settings: UserSettings? = null,
)

/** The part of an account's Seerr settings Hither reads: its own streaming region, empty for the server's. */
@Serializable
data class UserSettings(
    val streamingRegion: String? = null,
    val discoverRegion: String? = null,
    val locale: String? = null,
)

/** `GET /user/{id}/quota`: the account's limits for films and for series' seasons (Seerr's `QuotaResponse`). */
@Serializable
data class Quota(
    val movie: QuotaStatus = QuotaStatus(),
    val tv: QuotaStatus = QuotaStatus(),
)

/**
 * One limit: at most [limit] requests (seasons, for series) in [days] days; [remaining] left, and
 * [restricted] once none are. No [limit] means no limit.
 */
@Serializable
data class QuotaStatus(
    @Serializable(with = LenientIntSerializer::class)
    val days: Int? = null,
    @Serializable(with = LenientIntSerializer::class)
    val limit: Int? = null,
    val used: Int = 0,
    @Serializable(with = LenientIntSerializer::class)
    val remaining: Int? = null,
    val restricted: Boolean = false,
)
