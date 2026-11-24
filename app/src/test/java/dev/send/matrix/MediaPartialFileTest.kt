package dev.send.matrix

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaPartialFileTest {
    private val target = File("cache/media/abc/photo.jpg")

    @Test
    fun concurrentDownloadsOfOneMediaGetDistinctPartialFiles() {
        assertNotEquals(uniquePartialFile(target), uniquePartialFile(target))
    }

    @Test
    fun partialFileStaysBesideTheTargetAndIsNotTheSharedName() {
        val partial = uniquePartialFile(target)
        assertEquals(target.parentFile, partial.parentFile)
        assertTrue(partial.name.startsWith("photo.jpg."))
        assertTrue(partial.name.endsWith(".part"))
        assertNotEquals("photo.jpg.part", partial.name)
    }
}
