package com.tomdebres.hither.core.rules

import com.tomdebres.hither.core.model.PublicSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Which ways in a server offers (M8): only what `settings/public` switches on, Quick Connect only where Seerr has it. */
class SignInMethodsTest {
    private fun settings(local: Boolean = true, viaServer: Boolean = true, type: Int = 1, external: String = "") =
        PublicSettings(localLogin = local, mediaServerLogin = viaServer, mediaServerType = type, jellyfinExternalHost = external)

    @Test
    fun `a Plex server with both on offers the password and Plex - nothing of Jellyfin's`() {
        val methods = signInMethods(settings(type = 1), seerrVersion = null)
        assertEquals(SignInMethods(local = true, plex = true, mediaServerPassword = false, quickConnect = false, mediaServer = MediaServerKind.PLEX), methods)
        assertTrue(methods.anyMediaServer)
    }

    @Test
    fun `a Jellyfin server on 3-4 or later offers its username and password and Quick Connect`() {
        val methods = signInMethods(settings(type = 2), seerrVersion = "3.5.2")
        assertTrue(methods.mediaServerPassword)
        assertTrue(methods.quickConnect)
        assertFalse(methods.plex)
        assertEquals("Jellyfin", methods.mediaServer.title)
    }

    @Test
    fun `Quick Connect waits for Seerr 3-4 - an older or unread version gets username and password only`() {
        assertFalse(signInMethods(settings(type = 2), seerrVersion = "3.3.0").quickConnect)
        assertTrue(signInMethods(settings(type = 2), seerrVersion = "3.3.0").mediaServerPassword)
        assertFalse(signInMethods(settings(type = 2), seerrVersion = null).quickConnect)
        assertTrue(signInMethods(settings(type = 2), seerrVersion = "3.4.0").quickConnect)
    }

    @Test
    fun `Emby gets username and password - never Quick Connect - which Seerr has for Jellyfin only`() {
        val methods = signInMethods(settings(type = 3), seerrVersion = "3.5.2")
        assertTrue(methods.mediaServerPassword)
        assertFalse(methods.quickConnect)
        assertEquals("Emby", methods.mediaServer.title)
    }

    @Test
    fun `media-server sign-in off leaves the password alone - and both off leaves nothing`() {
        val local = signInMethods(settings(viaServer = false, type = 2), seerrVersion = "3.5.2")
        assertTrue(local.local)
        assertFalse(local.anyMediaServer)
        assertFalse(local.quickConnect)
        assertTrue(signInMethods(settings(local = false, viaServer = false), null).none)
    }

    @Test
    fun `local off on a Plex server leaves Plex only`() {
        val methods = signInMethods(settings(local = false, type = 1), null)
        assertFalse(methods.local)
        assertTrue(methods.plex)
        assertFalse(methods.none)
    }

    @Test
    fun `a server not set up - or of a type this build does not know - offers no media-server way`() {
        assertFalse(signInMethods(settings(type = 4), "3.5.2").anyMediaServer)
        assertFalse(signInMethods(settings(type = 9), "3.5.2").anyMediaServer)
        assertEquals(MediaServerKind.NONE, MediaServerKind.of(0))
    }

    @Test
    fun `the version is wanted for a Jellyfin server with media-server sign-in only`() {
        assertTrue(wantsSeerrVersion(settings(type = 2)))
        assertFalse(wantsSeerrVersion(settings(type = 3)))
        assertFalse(wantsSeerrVersion(settings(type = 1)))
        assertFalse(wantsSeerrVersion(settings(viaServer = false, type = 2)))
    }

    @Test
    fun `versions read as Seerr writes them`() {
        assertTrue(hasQuickConnect("3.4.0"))
        assertTrue(hasQuickConnect("v3.5.2"))
        assertTrue(hasQuickConnect("4.0.0"))
        assertTrue(hasQuickConnect("3.10.1"))
        assertTrue(hasQuickConnect("develop-18a90b4"))
        assertFalse(hasQuickConnect("3.3.0"))
        assertFalse(hasQuickConnect("2.7.3"))
        assertFalse(hasQuickConnect(""))
        assertFalse(hasQuickConnect("local"))
    }

    @Test
    fun `where to approve a code is Jellyfin's external address without its scheme - or nothing`() {
        assertEquals("jellyfin.example.com", quickConnectPlace(settings(type = 2, external = "https://jellyfin.example.com/")))
        assertEquals("10.0.0.5:8096", quickConnectPlace(settings(type = 2, external = "10.0.0.5:8096")))
        assertNull(quickConnectPlace(settings(type = 2, external = "  ")))
    }
}
