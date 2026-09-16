package com.jake.duolauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase22AppLayoutTest {
    private fun app(packageName: String, label: String = packageName, work: Boolean = false) =
        Phase22AppCandidate("$packageName/Main", packageName, label, work)

    @Test fun `preset keeps leading dock and widgets while using two app pages`() {
        val dock = listOf("phone/Main", "messages/Main", "wechat/Main", "camera/Main")
        val leading = List(HOME_CELLS) { index -> if (index == 20) "leading/Main" else null }
        val widgets = listOf(
            WidgetPlacement(0, CLOCK_WIDGET, 0, 0, 0, 2, 2),
            WidgetPlacement(1, DATE_WIDGET, 0, 2, 0, 2, 2),
            WidgetPlacement(2, WEATHER_WIDGET, -1, 0, 0, 4, 2),
        )
        val result = phase22AppLayout(HomeLayout(emptyList(), dock, widgets, leadingSlots = leading), listOf(
            app("org.localsend.localsend_app", "LocalSend"),
            app("com.microsoft.rdc.androidx", "Windows App"),
            app("com.miui.gallery", "相册"),
            app("com.larus.nova", "豆包"),
            app("com.openai.chatgpt", "ChatGPT"),
        ))

        assertEquals(dock, result.dock)
        assertEquals(leading, result.leadingSlots)
        assertEquals(widgets, result.widgetPlacements)
        assertEquals("org.localsend.localsend_app/Main", result.slots[8])
        assertEquals("com.microsoft.rdc.androidx/Main", result.slots[9])
        assertTrue(result.slots.drop(HOME_CELLS).filterNotNull().all(::isFolderId))
        assertEquals(listOf("豆包", "ChatGPT"), result.folders.single().appIds.map { id ->
            if (id.startsWith("com.larus")) "豆包" else "ChatGPT"
        })
    }

    @Test fun `overseas and work apps stay in All Apps and no shortcut is duplicated`() {
        val inputs = listOf(
            app("org.telegram.messenger", "Telegram"),
            app("com.grabtaxi.passenger", "Grab"),
            app("com.android.settings", "设置", work = true),
            app("com.larus.nova", "豆包"),
            app("com.openai.chatgpt", "ChatGPT"),
            app("com.tencent.mobileqq", "QQ"),
            app("com.tencent.wework", "企业微信"),
        )
        val result = phase22AppLayout(HomeLayout(emptyList(), listOf("com.tencent.mm/Main", null, null, null)), inputs)
        val placedApps = result.slots.filterNotNull().filterNot(::isFolderId) + result.folders.flatMap(FolderEntry::appIds)

        assertFalse(placedApps.any { it.startsWith("org.telegram") || it.startsWith("com.grabtaxi") })
        assertFalse(placedApps.any { it.startsWith("com.android.settings") })
        assertEquals(placedApps.size, placedApps.distinct().size)
        assertTrue(result.folders.any { it.title == "AI" })
        assertTrue(result.folders.any { it.title == "聊天社交" })
    }

    @Test fun `reapplying preset is deterministic`() {
        val apps = listOf(
            app("com.android.settings", "设置"), app("com.miui.gallery", "相册"),
            app("com.larus.nova", "豆包"), app("com.openai.chatgpt", "ChatGPT"),
        )
        val original = HomeLayout(listOf("old/Main"), List(4) { null }, DEFAULT_WIDGET_PLACEMENTS)
        val first = phase22AppLayout(original, apps)
        assertEquals(first, phase22AppLayout(first, apps))
    }
}
