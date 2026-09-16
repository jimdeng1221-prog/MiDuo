package com.jake.duolauncher

import androidx.compose.runtime.mutableStateOf
import org.junit.Assert.*
import org.junit.Test

class LicensedSelectionTest {
    @Test fun allAppAndFolderSelectionsRequireActivation() {
        var allowed = false
        var requests = 0
        val backing = mutableStateOf<String?>(null)
        val selection = LicensedSelectionState(backing, { allowed }, { requests++ })
        listOf("app-id", "folder:example", "folder-child").forEach {
            selection.value = it
            assertNull(selection.value)
            assertNull(backing.value)
        }
        assertEquals(3, requests)
        selection.value = null
        assertEquals(3, requests)
        allowed = true
        selection.value = "app-id"
        assertEquals("app-id", selection.value)
        allowed = false
        assertNull(selection.value)
    }
}
