package com.jake.duolauncher

import org.junit.Assert.*
import org.junit.Test

class WirelessAdbPolicyTest {
    @Test fun `ports reject hosts privileged ports and command injection`() {
        listOf("", "0", "80", "1023", "65536", "-1", "127.0.0.1:12345", "12345;id", "12345\n4321", "１２３４５").forEach {
            assertNull(it, localAdbPort(it))
        }
        assertEquals(1024, localAdbPort("1024")); assertEquals(65535, localAdbPort("65535"))
    }
    @Test fun `code keeps leading zeros and rejects non ASCII or non six digits`() {
        assertTrue(validAdbPairingCode("001234"))
        listOf("12345", "1234567", "１２３４５６", "12345 ", "12;456", " 123456").forEach { assertFalse(validAdbPairingCode(it)) }
    }
    @Test fun `license role and connected service are all required`() {
        for (l in listOf(true, false)) for (h in listOf(true, false)) for (s in listOf(true, false)) {
            assertEquals(l && h && s, wirelessSetupAllowed(l, h, s))
        }
    }
    @Test fun `only one fixed grant is allowed`() {
        assertEquals("pm grant com.jake.duolauncher android.permission.WRITE_SECURE_SETTINGS", MIDUO_NAVIGATION_GRANT)
    }
}
