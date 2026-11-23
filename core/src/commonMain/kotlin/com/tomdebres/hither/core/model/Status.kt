package com.tomdebres.hither.core.model

/** The two kinds of title Seerr requests, by the word its API uses. */
enum class MediaType(val api: String) {
    MOVIE("movie"),
    TV("tv");

    companion object {
        fun fromApi(value: String?): MediaType? = entries.firstOrNull { it.api == value }
    }
}

/**
 * `media.status` and `media.status4k` (Seerr's `MediaStatus`). Kept as the server's integers:
 * a value this build has never heard of must parse and fall through as "no badge", not crash.
 */
object MediaStatus {
    const val UNKNOWN = 1
    const val PENDING = 2
    const val PROCESSING = 3
    const val PARTIALLY_AVAILABLE = 4
    const val AVAILABLE = 5
    const val BLOCKLISTED = 6
    const val DELETED = 7
}

/** `request.status` (Seerr's `MediaRequestStatus`). */
object RequestStatus {
    const val PENDING = 1
    const val APPROVED = 2
    const val DECLINED = 3
    const val FAILED = 4
    const val COMPLETED = 5
}
