package com.tomdebres.hither.core.rules

import com.tomdebres.hither.core.ServerAddress

/** What is wrong with a sign-in form before anything is sent. */
enum class SignInProblem {
    /** Nothing usable was typed for the server. */
    SERVER,

    /** "Enter an email address." (Seerr's form said "You must provide a valid email address"). */
    EMAIL,

    /** "Enter a password." */
    PASSWORD,
}

/** A sign-in form that is fit to send: the base URL to call and the credentials as sent. */
data class SignInRequest(val baseUrl: String, val email: String, val password: String)

/**
 * Checks what was typed. Seerr's own form asks only that the email and the password are not
 * empty (the "email" may be a media-server username), so that is all that is asked here. The
 * email is trimmed, since a TV keyboard's suggestion bar likes to add a space and Seerr only
 * warns about one; the password is sent exactly as typed.
 */
fun checkSignIn(server: String, email: String, password: String): Pair<SignInRequest?, Set<SignInProblem>> {
    val problems = mutableSetOf<SignInProblem>()
    val baseUrl = ServerAddress.normalise(server)
    if (baseUrl == null) problems += SignInProblem.SERVER
    if (email.isBlank()) problems += SignInProblem.EMAIL
    if (password.isEmpty()) problems += SignInProblem.PASSWORD
    val request = if (problems.isEmpty() && baseUrl != null) SignInRequest(baseUrl, email.trim(), password) else null
    return request to problems
}
