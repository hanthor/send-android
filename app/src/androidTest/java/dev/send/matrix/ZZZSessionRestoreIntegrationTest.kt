package dev.send.matrix

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cold-start session restore, simulated in-process: the repository releases every SDK object (as process
 * death would), leaving only what is on disk, then restore() has to reopen the session from there.
 */
@RunWith(AndroidJUnit4::class)
class ZZZSessionRestoreIntegrationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun coldStartRestoreOfTheSignedInSession() {
        composeRule.signInAsAliceIfNeeded()
        val repository = MatrixRepository.get(context)

        repository.close()
        val restored = runCatching { runBlocking { repository.restore() } }

        // Demonstrates the reported bug before the fix: nothing on disk can reopen the session.
        assertTrue(
            "Cold-start restore unexpectedly succeeded: $restored",
            restored.isFailure || restored.getOrNull() == false,
        )
    }
}
