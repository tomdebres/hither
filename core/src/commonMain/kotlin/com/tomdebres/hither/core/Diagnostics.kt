package com.tomdebres.hither.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

// Settings › Diagnostics (M6, DECISIONS 107; audit item 20): the last failures the app recovered from, for
// Tom, not the family. What, when, the HTTP status, the call's method and API path; never the server's
// address, a query (search words) or anything the server sent.

/** What kind of failure: no answer, an answer with an error status, or an answer the client could not read. */
enum class FailureKind { NETWORK, HTTP, UNEXPECTED }

data class Failure(
    val atMillis: Long,
    val kind: FailureKind,
    /** "GET", "POST", ... */
    val method: String,
    /** The API path after `/api/v1`, without its query: "/movie/123". */
    val path: String,
    val status: Int? = null,
    /** For a network failure, the exception's class name ("SocketTimeoutException"), never its message (it can carry the address). */
    val cause: String? = null,
) {
    /** The row's words: "HTTP 500", "Network (SocketTimeoutException)", "Unexpected answer". */
    val what: String
        get() = when (kind) {
            FailureKind.HTTP -> "HTTP ${status ?: "?"}"
            FailureKind.NETWORK -> if (cause.isNullOrBlank()) "Network" else "Network ($cause)"
            FailureKind.UNEXPECTED -> "Unexpected answer"
        }
}

/** The last [capacity] failures, newest first; [now] is the clock (the app's, a test's). Safe from any thread. */
class FailureLog(private val now: () -> Long, val capacity: Int = DIAGNOSTICS_KEPT) {
    private val state = MutableStateFlow<List<Failure>>(emptyList())

    val failures: StateFlow<List<Failure>> get() = state

    fun record(kind: FailureKind, method: String, path: String, status: Int? = null, cause: String? = null) {
        val failure = Failure(now(), kind, method, loggedPath(path), status, cause)
        state.update { (listOf(failure) + it).take(capacity) }
    }

    fun clear() {
        state.value = emptyList()
    }
}

/** Failures kept: SeerrTV's Diagnostics keeps a screenful or two; fifty is the audit's number. */
const val DIAGNOSTICS_KEPT = 50

/** A path as logged: from after `/api/v1` (any base path before it goes too), without a query or fragment. */
fun loggedPath(path: String): String {
    val bare = path.substringBefore('?').substringBefore('#')
    val api = bare.indexOf("/api/v1")
    return (if (api >= 0) bare.substring(api + "/api/v1".length) else bare).ifEmpty { "/" }
}
