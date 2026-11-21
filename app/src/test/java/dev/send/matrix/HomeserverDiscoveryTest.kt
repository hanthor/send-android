package dev.send.matrix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The sign-in server field accepts a URL, a server name or a Matrix ID; these helpers sort out which. */
class HomeserverDiscoveryTest {
    @Test
    fun matrixIdYieldsItsServerName() {
        assertEquals("reilly.asia", serverNameFromMxid("@james:reilly.asia"))
        assertEquals("reilly.asia", serverNameFromMxid("  @james:reilly.asia \n"))
    }

    @Test
    fun matrixIdKeepsThePortOfItsServerName() {
        assertEquals("reilly.asia:8448", serverNameFromMxid("@james:reilly.asia:8448"))
    }

    @Test
    fun matrixIdWithoutTheSigilStillCounts() {
        assertEquals("reilly.asia", serverNameFromMxid("james:reilly.asia"))
    }

    @Test
    fun malformedMatrixIdsAreRejected() {
        assertNull("no server part", serverNameFromMxid("@james"))
        assertNull("empty server part", serverNameFromMxid("@james:"))
        assertNull("empty localpart", serverNameFromMxid("@:reilly.asia"))
        assertNull("whitespace in server", serverNameFromMxid("@james:reilly .asia"))
        assertNull("path in server", serverNameFromMxid("@james:reilly.asia/path"))
        assertNull("blank", serverNameFromMxid("   "))
    }

    @Test
    fun serverNamesAndUrlsAreNotMatrixIds() {
        assertNull(serverNameFromMxid("reilly.asia"))
        assertNull(serverNameFromMxid("https://matrix.example.org"))
        assertNull(serverNameFromMxid("https://matrix.example.org:8448"))
        assertNull(serverNameFromMxid("matrix.example.org:8448/path"))
    }

    @Test
    fun hostAndPortIsNotReadAsUserAndServer() {
        assertNull("numeric port", serverNameFromMxid("reilly.asia:8448"))
        assertNull("dotless host", serverNameFromMxid("localhost:8008"))
        assertNull("dotless suffix", serverNameFromMxid("james:localhost"))
    }

    @Test
    fun serverNameValidationRejectsSeparatorsAndWhitespace() {
        assertTrue("reilly.asia".isValidServerName())
        assertTrue("reilly.asia:8448".isValidServerName())
        assertFalse("".isValidServerName())
        for (bad in listOf("a b", "a\tb", "a/b", "a?b", "a#b", "a@b")) {
            assertFalse("'$bad' should be invalid", bad.isValidServerName())
        }
    }

    @Test
    fun typedServerWinsOverTheUsername() {
        assertEquals("matrix.example.org", effectiveServerInput("  matrix.example.org ", "@james:reilly.asia"))
    }

    @Test
    fun blankServerFallsBackToTheMatrixIdInTheUsername() {
        assertEquals("reilly.asia", effectiveServerInput("", "@james:reilly.asia"))
        assertEquals("reilly.asia", effectiveServerInput("   ", "@james:reilly.asia"))
    }

    @Test
    fun blankServerAndPlainUsernameLeaveNothingToConnectTo() {
        assertEquals("", effectiveServerInput("", "james"))
        assertEquals("", effectiveServerInput("", ""))
    }
}
