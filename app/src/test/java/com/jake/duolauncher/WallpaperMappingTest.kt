package com.jake.duolauncher

import org.junit.Assert.assertEquals
import org.junit.Test

class WallpaperMappingTest {
    @Test fun `selected C uses exact inner and cover crops from one runtime image`() {
        assertEquals(
            WallpaperSourceCrop(x = 0, y = 20, width = 2364, height = 1672),
            wallpaperSourceCrop(2364, 1712, 2364, 1672, rightAnchored = true),
        )
        assertEquals(
            WallpaperSourceCrop(x = 1196, y = 0, width = 1168, height = 1712),
            wallpaperSourceCrop(2364, 1712, 1168, 1712, rightAnchored = true),
        )
    }

    @Test fun `user selected photos retain centred cover crop`() {
        assertEquals(
            WallpaperSourceCrop(x = 598, y = 0, width = 1168, height = 1712),
            wallpaperSourceCrop(2364, 1712, 1168, 1712, rightAnchored = false),
        )
    }
}
