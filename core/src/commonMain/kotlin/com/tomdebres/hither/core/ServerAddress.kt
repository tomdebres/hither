package com.tomdebres.hither.core

/**
 * What the user types for the Seerr server, made into the base URL the client calls.
 * Seerr's API lives under `/api/v1`; the user may paste the server page, the API page or
 * just a host and port, as the web UI's own address field accepts.
 */
object ServerAddress {
    /** The base URL without a trailing slash, or null when nothing usable was typed. */
    fun normalise(typed: String): String? {
        var s = typed.trim()
        if (s.isEmpty()) return null
        if (!s.startsWith("http://", ignoreCase = true) && !s.startsWith("https://", ignoreCase = true)) {
            s = "http://$s"
        }
        s = s.trimEnd('/')
        if (s.endsWith("/api/v1", ignoreCase = true)) s = s.dropLast("/api/v1".length)
        s = s.trimEnd('/')
        // "http://" alone, or a scheme followed by a path and no host, is nothing usable.
        val host = s.substringAfter("://", missingDelimiterValue = "")
        if (host.isEmpty() || host.startsWith("/")) return null
        return s
    }
}
