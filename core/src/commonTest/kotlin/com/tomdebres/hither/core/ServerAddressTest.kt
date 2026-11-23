package com.tomdebres.hither.core

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.Test

class ServerAddressTest {
    @Test
    fun hostAndPortGetHttp() = assertEquals("http://192.0.2.10:5055", ServerAddress.normalise("192.0.2.10:5055"))

    @Test
    fun httpsIsKept() = assertEquals("https://seerr.example.com", ServerAddress.normalise("https://seerr.example.com/"))

    @Test
    fun theApiPathIsDropped() = assertEquals("http://192.0.2.10:5055", ServerAddress.normalise("http://192.0.2.10:5055/api/v1/"))

    @Test
    fun surroundingSpaceIsDropped() = assertEquals("http://seerr.lan", ServerAddress.normalise("  seerr.lan  "))

    @Test
    fun emptyIsNothing() {
        assertNull(ServerAddress.normalise(""))
        assertNull(ServerAddress.normalise("   "))
        assertNull(ServerAddress.normalise("http://"))
    }
}
