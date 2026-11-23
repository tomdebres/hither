@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.tomdebres.hither.core

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecItemUpdate
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import platform.darwin.OSStatus

/**
 * A [CookieVault] in the Keychain: one generic-password item per server ([account], the base URL)
 * under [service]. tvOS may purge an app's files, but not its Keychain (docs/app-store-tvos.md), so a
 * sign-in survives. Readable after the first unlock (an Apple TV has no lock screen; a Top Shelf
 * extension may read it in the background), on this device only, never synced.
 */
class KeychainVault(private val account: String, private val service: String = SERVICE) : CookieVault {
    override fun read(): String? = memScoped {
        val result = alloc<CFTypeRefVar>()
        val status = query(
            kSecReturnData to kCFBooleanTrue,
            kSecMatchLimit to kSecMatchLimitOne,
        ) { SecItemCopyMatching(it, result.ptr) }
        when (status) {
            errSecSuccess -> (CFBridgingRelease(result.value) as? NSData)?.let { NSString.create(data = it, encoding = NSUTF8StringEncoding)?.toString() }
            errSecItemNotFound -> null
            else -> throw KeychainException("read", status)
        }
    }

    override fun write(text: String) {
        val data = NSString.create(string = text).dataUsingEncoding(NSUTF8StringEncoding) ?: throw KeychainException("encode", 0)
        val value = CFBridgingRetain(data)
        try {
            val updated = query { match ->
                attributes(kSecValueData to value) { SecItemUpdate(match, it) }
            }
            val status = if (updated == errSecItemNotFound) {
                query(kSecValueData to value, kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly) { SecItemAdd(it, null) }
            } else {
                updated
            }
            if (status != errSecSuccess) throw KeychainException("write", status)
        } finally {
            CFRelease(value)
        }
    }

    override fun delete() {
        val status = query { SecItemDelete(it) }
        if (status != errSecSuccess && status != errSecItemNotFound) throw KeychainException("delete", status)
    }

    /** This item's query (class, service, account) with [extra], for the length of [block]. */
    private fun <T> query(vararg extra: Pair<CFStringRef?, CFTypeRef?>, block: (CFMutableDictionaryRef?) -> T): T {
        val serviceRef = CFBridgingRetain(NSString.create(string = service))
        val accountRef = CFBridgingRetain(NSString.create(string = account))
        try {
            return attributes(kSecClass to kSecClassGenericPassword, kSecAttrService to serviceRef, kSecAttrAccount to accountRef, *extra, block = block)
        } finally {
            CFRelease(serviceRef)
            CFRelease(accountRef)
        }
    }

    /** A dictionary of [pairs] for the length of [block]. */
    private fun <T> attributes(vararg pairs: Pair<CFStringRef?, CFTypeRef?>, block: (CFMutableDictionaryRef?) -> T): T {
        val dictionary = CFDictionaryCreateMutable(null, pairs.size.convert(), kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
        try {
            pairs.forEach { (key, value) -> CFDictionaryAddValue(dictionary, key, value) }
            return block(dictionary)
        } finally {
            CFRelease(dictionary)
        }
    }

    companion object {
        /** The app's service name for its session items. */
        const val SERVICE = "com.tomdebres.heerr.session"
    }
}

/** The Keychain refused: [status] is its OSStatus (-34018 is a build without the Keychain entitlement). */
class KeychainException(val operation: String, val status: OSStatus) : IllegalStateException("Keychain $operation failed: OSStatus $status")
