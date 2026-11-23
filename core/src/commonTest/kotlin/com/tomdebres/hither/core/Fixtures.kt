package com.tomdebres.hither.core

import kotlinx.serialization.serializer

/**
 * Recorded server responses, `src/commonTest/fixtures` (see its README), read the same way on every
 * target: the build writes them out as Kotlin (`fixtureSources` in core/build.gradle.kts).
 */
object Fixtures {
    fun text(name: String): String = checkNotNull(FIXTURE_FILES[name]) { "no fixture $name" }

    inline fun <reified T> read(name: String): T = SeerrClient.json.decodeFromString(serializer<T>(), text(name))
}
