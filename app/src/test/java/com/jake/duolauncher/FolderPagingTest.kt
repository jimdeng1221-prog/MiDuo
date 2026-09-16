package com.jake.duolauncher

import org.junit.Assert.assertEquals
import org.junit.Test

class FolderPagingTest {
    @Test fun `folder uses fixed nine app horizontal pages`() {
        assertEquals(1, folderPageCount(0))
        assertEquals(1, folderPageCount(1))
        assertEquals(1, folderPageCount(9))
        assertEquals(2, folderPageCount(10))
        assertEquals(2, folderPageCount(18))
        assertEquals(3, folderPageCount(19))
    }
}
