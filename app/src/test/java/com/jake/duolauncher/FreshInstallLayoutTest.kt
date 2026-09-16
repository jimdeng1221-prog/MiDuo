package com.jake.duolauncher

import org.junit.Assert.*
import org.junit.Test

class FreshInstallLayoutTest {
    private fun app(pkg: String, work: Boolean = false) = Phase22AppCandidate("$pkg/Main", pkg, pkg, work)

    @Test fun `Xiaomi dock uses phone messages browser camera in that order`() {
        val pkgs = listOf("com.android.contacts", "com.android.mms", "com.android.browser", "com.android.camera")
        assertEquals(pkgs.map { "$it/Main" }, freshInstallDock((pkgs + "com.android.chrome" + "com.spotify.music").map { app(it) }))
    }
    @Test fun `missing dock apps stay empty and work apps are excluded`() {
        assertEquals(List<String?>(4) { null }, freshInstallDock(listOf(app("com.spotify.music"), app("com.android.camera", true))))
    }
    @Test fun `fresh home uses installed domestic apps and second page categories without duplicates`() {
        val apps = listOf("com.android.contacts", "com.android.mms", "com.android.browser", "com.android.camera",
            "com.miui.gallery", "com.android.settings", "com.tencent.mm", "com.eg.android.AlipayGphone",
            "com.miui.calculator", "com.miui.notes", "com.taobao.taobao", "com.jingdong.app.mall",
            "com.unknown.app", "com.spotify.music").map { app(it) }
        val dock = freshInstallDock(apps)
        val base = HomeLayout(emptyList(), dock, DEFAULT_WIDGET_PLACEMENTS)
        val result = phase22AppLayout(base, apps, freshInstall = true)
        assertEquals(dock, result.dock)
        assertEquals(base.widgetPlacements, result.widgetPlacements)
        assertEquals(setOf("实用工具", "时尚购物"), result.folders.map { it.title }.toSet())
        assertTrue(result.slots.take(HOME_CELLS).contains("com.tencent.mm/Main"))
        assertTrue(result.slots.drop(HOME_CELLS).filterNotNull().all(::isFolderId))
        val placed = result.slots.filterNotNull().filterNot(::isFolderId) + result.folders.flatMap { it.appIds } + dock.filterNotNull()
        assertEquals(placed.distinct(), placed)
        assertTrue(placed.all { id -> apps.any { it.id == id } })
        assertFalse(placed.contains("com.unknown.app/Main"))
        assertEquals(result, phase22AppLayout(result, apps, freshInstall = true))
    }
    @Test fun `English installation uses English folder names`() {
        val layout = phase22AppLayout(HomeLayout(emptyList(), List(4) { null }),
            listOf(app("com.miui.calculator"), app("com.miui.notes")), freshInstall = true, english = true)
        assertEquals("Tools", layout.folders.single().title)
    }
    @Test fun `new home artwork is slightly larger than dock on both panels with unchanged grid pitch`() {
        val cover = homeGeometry(425f, 608f, LayoutPreset(), true)
        val inner = homeGeometry(860f, 608f, LayoutPreset(), true)
        assertEquals(48f, cover.iconSize)
        assertEquals(dockIconSize() + 4f, cover.iconSize)
        assertEquals(cover.iconSize, inner.iconSize)
        assertEquals(313f, cover.gridWidth)
        assertEquals(cover.rowHeight, homeGeometry(425f, 608f, LayoutPreset(iconSize = 54f), true).rowHeight)
    }
    @Test fun `beta14 and beta15 defaults are adjusted once without changing other choices`() {
        assertEquals(48f, upgradeArtworkDefault(LayoutPreset(iconSize = 44f), 0).iconSize)
        assertEquals(48f, upgradeArtworkDefault(LayoutPreset(iconSize = 46f), 1).iconSize)
        assertEquals(44f, upgradeArtworkDefault(LayoutPreset(iconSize = 44f), 1).iconSize)
        listOf(40f, 46f, 48f, 54f).forEach { value ->
            assertEquals(value, upgradeArtworkDefault(LayoutPreset(iconSize = value), 0).iconSize)
        }
        listOf(40f, 44f, 46f, 48f, 54f).forEach { value ->
            assertEquals(value, upgradeArtworkDefault(LayoutPreset(iconSize = value), 2).iconSize)
        }
    }
}
