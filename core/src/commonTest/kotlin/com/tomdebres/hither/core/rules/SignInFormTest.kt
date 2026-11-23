package com.tomdebres.hither.core.rules

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.Test

class SignInFormTest {
    @Test
    fun `a complete form gives the base URL and the credentials as they will be sent`() {
        val (request, problems) = checkSignIn(" seerr.test:5055/ ", " someone@example.invalid ", " pass word ")
        assertEquals(emptySet<SignInProblem>(), problems)
        // the address is normalised, the email trimmed, the password left exactly as typed
        assertEquals(SignInRequest("http://seerr.test:5055", "someone@example.invalid", " pass word "), request)
    }

    @Test
    fun `each empty field is named - and nothing is fit to send`() {
        val (request, problems) = checkSignIn("", "  ", "")
        assertNull(request)
        assertEquals(setOf(SignInProblem.SERVER, SignInProblem.EMAIL, SignInProblem.PASSWORD), problems)
    }

    @Test
    fun `one bad field is enough to hold the form back`() {
        assertEquals(setOf(SignInProblem.SERVER), checkSignIn("http://", "a", "b").second)
        assertEquals(setOf(SignInProblem.EMAIL), checkSignIn("seerr.test", "", "b").second)
        assertEquals(setOf(SignInProblem.PASSWORD), checkSignIn("seerr.test", "a", "").second)
        assertNull(checkSignIn("seerr.test", "a", "").first)
    }

    @Test
    fun `a username is accepted where an email would be`() {
        assertEquals("plexuser", checkSignIn("seerr.test", "plexuser", "b").first?.email)
    }
}
