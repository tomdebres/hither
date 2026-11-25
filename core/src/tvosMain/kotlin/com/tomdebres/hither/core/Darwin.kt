package com.tomdebres.hither.core

import com.tomdebres.hither.core.rules.ImageSource
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.cookies.CookiesStorage

/**
 * The HTTP engine on tvOS: Ktor's Darwin engine, Apple's URLSession underneath. Ktor gives the
 * URLSession no cookie storage of its own, so the client's ([SessionCookies] over the Keychain) is the
 * only place a session lives and forgetting it signs the device out; `DarwinSessionTest` goes red if
 * that ever changes. The Darwin engine has no connect timeout: the client's request timeout applies.
 */
fun darwinEngine(): HttpClientEngine = Darwin.create()

/**
 * A [SeerrClient] for [baseUrl] on tvOS: the Darwin engine and the given cookie storage. [tmdbImages] is TMDB's image
 * server ([ImageSource.TMDB_IMAGES]) in every release build; a debug build's test may name fake-seerr's stand-in.
 */
fun darwinSeerrClient(
    baseUrl: String,
    cookies: CookiesStorage,
    failures: FailureLog? = null,
    tmdbImages: String = ImageSource.TMDB_IMAGES,
): SeerrClient = SeerrClient(darwinEngine(), baseUrl, cookies, failures, tmdbImages)

/** The session's cookies for [baseUrl], kept in the Keychain ([KeychainVault]) between launches. */
fun keychainCookies(baseUrl: String, service: String = KeychainVault.SERVICE): SessionCookies =
    SessionCookies(baseUrl, KeychainVault(account = baseUrl, service = service))
