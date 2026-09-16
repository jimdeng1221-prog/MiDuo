package com.jake.duolauncher

import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** Verifier only. No issuer secret or universal activation code exists in the APK. */
internal object OfflineLicense {
    const val PUBLIC_KEY = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE3RqRvkay4jA3yYKjWUkvH/h4VLVHUij0ldPowpVKzU4noYR2DY6vCkZx6Q8VSKBUYDdFnmzQodvVyNWcRYwpBQ=="
    fun deviceCode(androidId: String?, packageName: String): String? = androidId
        ?.takeIf { it.isNotBlank() && it != "9774d56d682e549c" }
        ?.let { MessageDigest.getInstance("SHA-256").digest("miduo-device-v1|$packageName|$it".toByteArray(Charsets.UTF_8))
            .take(16).joinToString("") { byte -> "%02X".format(byte.toInt() and 255) } }

    fun normalize(code: String): String = code.filterNot(Char::isWhitespace)

    fun verify(code: String, device: String?, publicKey: String = PUBLIC_KEY): Boolean = runCatching {
        if (device == null || !device.matches(Regex("[0-9A-F]{32}")) || code.length > 512) return false
        val parts = normalize(code).split('.')
        if (parts.size != 3 || parts[0] != "MIDUO1" || parts[1] != device) return false
        val signature = Base64.getUrlDecoder().decode(parts[2])
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKey)))
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(key); update("MIDUO1.$device".toByteArray(Charsets.UTF_8)); verify(signature)
        }
    }.getOrDefault(false)
}

internal fun licensedSheet(requested: String, activated: Boolean): String =
    if (!activated && requested in setOf("settings:wallpaper", "widgets", "widgetActions", "dock", "pins")) "activation" else requested
