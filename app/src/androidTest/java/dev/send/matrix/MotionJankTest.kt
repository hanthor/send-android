package dev.send.matrix

import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.util.regex.Pattern
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Frame-timing check for phase 7 motion: real input (UiAutomator swipes and taps, not the Compose test clock) on the
 * app's navigation fixture scrolls a 200-row conversation list down and back and opens/closes a conversation three
 * times with animations on, then reads `dumpsys gfxinfo <package> framestats`. The summary is written to
 * Download/Send/send-motion-jank.txt (CI prints it) and the run fails above [MAX_JANKY_PERCENT].
 */
@RunWith(AndroidJUnit4::class)
class MotionJankTest {
    @Test
    fun listScrollAndConversationOpenCloseStayWithinTheJankBudget() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val packageName = instrumentation.targetContext.packageName
        // CI turns animations off; the transitions under test need them on (Compose follows this scale).
        val previousScale = device.executeShellCommand("settings get global animator_duration_scale").trim()
            .takeIf { it.isNotEmpty() && it != "null" } ?: "1"
        device.executeShellCommand("settings put global animator_duration_scale 1")
        try {
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent { SendTheme { MotionFixture(motionRooms(ROWS), motionChat(40)) } }
                }
                val mayaRow = By.text(Pattern.compile("Maya(\\W.*)?", Pattern.DOTALL))
                check(device.wait(Until.hasObject(mayaRow), 10_000)) { "The conversation list didn't appear." }
                device.waitForIdle()
                SystemClock.sleep(500)
                device.executeShellCommand("dumpsys gfxinfo $packageName reset")

                val x = device.displayWidth / 2
                val low = device.displayHeight * 3 / 4
                val high = device.displayHeight / 4
                // Fling down the list and back up (12 steps ≈ 60ms per swipe: a fling, as a thumb would).
                repeat(8) { device.swipe(x, low, x, high, 12) }
                SystemClock.sleep(800)
                repeat(8) { device.swipe(x, high, x, low, 12) }
                var guard = 0
                while (!device.hasObject(mayaRow) && guard++ < 30) device.swipe(x, high, x, low, 12)
                SystemClock.sleep(800)

                repeat(3) {
                    device.findObject(mayaRow).click()
                    check(device.wait(Until.hasObject(By.desc("Back")), 5_000)) { "The conversation didn't open." }
                    SystemClock.sleep(700)
                    device.pressBack()
                    check(device.wait(Until.gone(By.desc("Back")), 5_000)) { "The conversation didn't close." }
                    SystemClock.sleep(700)
                }

                val dump = device.executeShellCommand("dumpsys gfxinfo $packageName framestats")
                val total = Regex("Total frames rendered: (\\d+)").find(dump)?.groupValues?.get(1)?.toInt() ?: 0
                val janky = Regex("Janky frames: (\\d+) \\(([\\d.]+)%\\)").find(dump)
                val jankyFrames = janky?.groupValues?.get(1)?.toInt() ?: 0
                val jankyPercent = janky?.groupValues?.get(2)?.toFloat() ?: 100f
                val percentiles = Regex("(\\d+)th percentile: (\\d+)ms").findAll(dump).take(4)
                    .joinToString(" · ") { "p${it.groupValues[1]} ${it.groupValues[2]}ms" }
                val legacy = Regex("Janky frames \\(legacy\\): (\\d+) \\(([\\d.]+)%\\)").find(dump)
                    ?.let { "legacy (>16ms) ${it.groupValues[1]} (${it.groupValues[2]}%)" }.orEmpty()
                val summary = "Motion jank: $jankyFrames janky of $total frames ($jankyPercent%, limit $MAX_JANKY_PERCENT%) · " +
                    "$percentiles · $legacy · 200-row list fling ×16, conversation open/close ×3"
                Log.i("MotionJankTest", summary)
                writeAuditDownload("send-motion-jank.txt", "text/plain") { out ->
                    out.write("$summary\n\n$dump".toByteArray())
                }
                assertTrue("Too few frames to judge: $summary", total >= MIN_FRAMES)
                assertTrue(summary, jankyPercent <= MAX_JANKY_PERCENT)
            }
        } finally {
            device.executeShellCommand("settings put global animator_duration_scale $previousScale")
        }
    }

    private companion object {
        const val ROWS = 200
        const val MIN_FRAMES = 60

        /** Upper bound for HWUI's "Janky frames" share on the CI emulator; see IMPLEMENTATION_STATUS.md for the baseline. */
        const val MAX_JANKY_PERCENT = 50f
    }
}
