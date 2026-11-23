package com.tomdebres.hither.core

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test

class PermissionTest {
    @Test
    fun `a held bit is found among the wanted ones`() {
        val held = Permission.REQUEST or Permission.AUTO_APPROVE
        assertTrue(Permission.hasAny(held, Permission.REQUEST))
        assertTrue(Permission.hasAny(held, Permission.REQUEST_4K, Permission.AUTO_APPROVE))
    }

    @Test
    fun `a bit that is not held is not granted by its neighbours`() {
        val held = Permission.REQUEST
        assertFalse(Permission.hasAny(held, Permission.REQUEST_4K, Permission.REQUEST_4K_MOVIE))
        assertFalse(Permission.hasAny(held, Permission.MANAGE_REQUESTS))
    }

    @Test
    fun `an admin holds everything`() {
        assertTrue(Permission.hasAny(Permission.ADMIN, Permission.REQUEST_4K_TV))
    }

    @Test
    fun `no value and zero hold nothing`() {
        assertFalse(Permission.hasAny(null, Permission.REQUEST))
        assertFalse(Permission.hasAny(0, Permission.REQUEST))
    }
}
