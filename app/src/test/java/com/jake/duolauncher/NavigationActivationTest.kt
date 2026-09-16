package com.jake.duolauncher

import org.junit.Assert.*
import org.junit.Test

class NavigationActivationTest {
    private var mode = false
    private var writes = 0
    private var enabled = false
    private var hidden = false
    private fun activate(licensed: Boolean = true, defaultHome: Boolean = true,
        write: () -> Boolean = { mode = true; true }, hide: () -> Unit = { hidden = true }) =
        activateNavigation(licensed, defaultHome, { mode }, { writes++; write() }, { enabled = true }, hide)

    @Test fun existingGestureModeNeedsNoPrivilegedModeWrite() {
        mode = true
        assertEquals(NavigationEnableResult.ENABLED, activate(write = { throw SecurityException() }))
        assertEquals(0, writes)
        assertTrue(enabled)
    }
    @Test fun cosmeticPillFailureDoesNotPreventNavigation() {
        mode = true
        assertEquals(NavigationEnableResult.ENABLED, activate(hide = { throw SecurityException() }))
        assertTrue(enabled)
    }
    @Test fun unauthorizedNeverChangesAnything() {
        assertEquals(NavigationEnableResult.LICENSE_REQUIRED, activate(licensed = false))
        assertEquals(0, writes); assertFalse(enabled); assertFalse(hidden)
    }
    @Test fun nonDefaultLauncherNeverChangesAnything() {
        assertEquals(NavigationEnableResult.DEFAULT_HOME_REQUIRED, activate(defaultHome = false))
        assertEquals(0, writes); assertFalse(enabled); assertFalse(hidden)
    }
    @Test fun deniedSystemPermissionLeavesOverlayPreferenceUntouched() {
        assertEquals(NavigationEnableResult.SYSTEM_MODE_REQUIRED, activate(write = { throw SecurityException() }))
        assertFalse(enabled); assertFalse(hidden)
    }
    @Test fun failedWriteLeavesOverlayPreferenceUntouched() {
        assertEquals(NavigationEnableResult.SYSTEM_MODE_REQUIRED, activate(write = { false }))
        assertFalse(enabled); assertFalse(hidden)
    }
    @Test fun successfulWriteMustBeConfirmedByReadback() {
        assertEquals(NavigationEnableResult.SYSTEM_MODE_REQUIRED, activate(write = { true }))
        assertFalse(enabled); assertFalse(hidden)
    }
    @Test fun confirmedModeEnablesOverlay() {
        assertEquals(NavigationEnableResult.ENABLED, activate())
        assertEquals(1, writes); assertTrue(enabled); assertTrue(hidden)
    }
}
