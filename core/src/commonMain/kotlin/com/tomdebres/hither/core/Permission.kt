package com.tomdebres.hither.core

/**
 * Seerr's permission bits (`server/lib/permissions.ts` in the Seerr repository; the values are
 * part of its API). A user's `permissions` is the sum of the bits they hold.
 */
object Permission {
    const val ADMIN = 2
    const val MANAGE_USERS = 8
    const val MANAGE_REQUESTS = 16
    const val REQUEST = 32
    const val AUTO_APPROVE = 128
    const val AUTO_APPROVE_MOVIE = 256
    const val AUTO_APPROVE_TV = 512
    const val REQUEST_4K = 1024
    const val REQUEST_4K_MOVIE = 2048
    const val REQUEST_4K_TV = 4096
    const val REQUEST_VIEW = 16384
    const val AUTO_APPROVE_4K = 32768
    const val AUTO_APPROVE_4K_MOVIE = 65536
    const val AUTO_APPROVE_4K_TV = 131072
    const val REQUEST_MOVIE = 262144
    const val REQUEST_TV = 524288
    const val MANAGE_ISSUES = 1048576
    const val VIEW_ISSUES = 2097152
    const val CREATE_ISSUES = 4194304
    const val RECENT_VIEW = 67108864
    const val MANAGE_BLOCKLIST = 268435456

    /**
     * Whether [held] includes any of [wanted], as Seerr's `hasPermission(..., { type: 'or' })`
     * decides it: an admin holds everything. A missing value (the sign-in response gives none
     * worth trusting) holds nothing.
     */
    fun hasAny(held: Int?, vararg wanted: Int): Boolean {
        val value = held ?: return false
        if (value and ADMIN != 0) return true
        return wanted.any { value and it != 0 }
    }
}
