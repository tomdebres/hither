package com.tomdebres.hither.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Settings' server row says where the server is as the family reads it (tvOS's F37, `SettingsView.address`): the host,
 * its port and any path, without the scheme or a trailing slash.
 */
class ServerPlaceTest {
    @Test
    fun theSchemeAndTheTrailingSlashGo() {
        assertEquals("192.0.2.10:5055", serverPlace("http://192.0.2.10:5055"))
        assertEquals("seerr.example.invalid", serverPlace("https://seerr.example.invalid/"))
        assertEquals("seerr.test:5055", serverPlace("http://seerr.test:5055"))
    }

    @Test
    fun aPathStays() {
        assertEquals("example.invalid/seerr", serverPlace("https://example.invalid/seerr/"))
    }

    @Test
    fun whatCannotBeReadIsShownAsItIs() {
        assertEquals("not a url", serverPlace("not a url"))
        assertEquals("seerr.test:5055", serverPlace("seerr.test:5055"))
    }
}
