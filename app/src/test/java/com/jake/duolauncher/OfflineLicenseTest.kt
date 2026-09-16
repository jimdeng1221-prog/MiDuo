package com.jake.duolauncher

import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class OfflineLicenseTest {
    private val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val publicKey = Base64.getEncoder().encodeToString(keys.public.encoded)
    private val device = "0123456789ABCDEF0123456789ABCDEF"
    private fun sign(payload: String = "MIDUO1.$device"): String {
        val bytes = Signature.getInstance("SHA256withECDSA").run { initSign(keys.private); update(payload.toByteArray()); sign() }
        return "$payload.${Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)}"
    }
    @Test fun validSignatureUnlocksOnlyItsDevice() {
        val code = sign()
        assertTrue(OfflineLicense.verify(code, device, publicKey))
        assertFalse(OfflineLicense.verify(code, "F".repeat(32), publicKey))
        assertFalse(OfflineLicense.verify(code, null, publicKey))
        assertFalse(OfflineLicense.verify(code, device)) // A locally generated attacker key is not trusted.
    }
    @Test fun tamperedPayloadOrSignatureFails() {
        val code = sign()
        assertFalse(OfflineLicense.verify(code.replace("MIDUO1", "MIDUO2"), device, publicKey))
        assertFalse(OfflineLicense.verify(code.replace(device, "F".repeat(32)), "F".repeat(32), publicKey))
        assertFalse(OfflineLicense.verify("MIDUO1.$device.AAAA", device, publicKey))
        assertFalse(OfflineLicense.verify("MIDUO1.$device.%%%", device, publicKey))
    }
    @Test fun whitespaceCanBePastedButMalformedInputFailsClosed() {
        val code = sign()
        assertTrue(OfflineLicense.verify(" \n$code\n ", device, publicKey))
        listOf("", "true", "activated", code + ".extra", "a".repeat(513)).forEach {
            assertFalse(OfflineLicense.verify(it, device, publicKey))
        }
    }
    @Test fun identityIsStableAndScoped() {
        assertEquals(OfflineLicense.deviceCode("abc", "app"), OfflineLicense.deviceCode("abc", "app"))
        assertNotEquals(OfflineLicense.deviceCode("abc", "app"), OfflineLicense.deviceCode("def", "app"))
        assertNotEquals(OfflineLicense.deviceCode("abc", "app"), OfflineLicense.deviceCode("abc", "other"))
        assertEquals(32, OfflineLicense.deviceCode("abc", "app")!!.length)
        assertNull(OfflineLicense.deviceCode(null, "app"))
        assertNull(OfflineLicense.deviceCode("", "app"))
        assertNull(OfflineLicense.deviceCode("9774d56d682e549c", "app"))
    }
    @Test fun protectedRoutesCannotBypassActivationAndRecoveryRemainsOpen() {
        listOf("widgets", "widgetActions", "settings:wallpaper", "dock", "pins").forEach {
            assertEquals("activation", licensedSheet(it, false))
            assertEquals(it, licensedSheet(it, true))
        }
        listOf("", "settings", "activation").forEach { assertEquals(it, licensedSheet(it, false)) }
    }
}
