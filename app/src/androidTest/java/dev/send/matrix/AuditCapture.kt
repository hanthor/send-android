package dev.send.matrix

import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File

/**
 * Full-device screenshot + uiautomator hierarchy for the Google Messages visual audit
 * (`dev/compare/compare.py` pairs `send-<name>-device.png` with `gm-<name>.png`).
 */
internal fun ComposeTestRule.auditShot(name: String) {
    if (mainClock.autoAdvance) {
        waitForIdle()
    } else {
        // A paused test clock (screens with endless timers) holds frames back from the display: pump some.
        repeat(20) {
            mainClock.advanceTimeByFrame()
            Thread.sleep(32)
        }
    }
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val device = UiDevice.getInstance(instrumentation)
    // Tests that copy text leave SystemUI's clipboard overlay on screen; it isn't part of Send.
    device.findObject(androidx.test.uiautomator.By.res("com.android.systemui", "dismiss_button"))?.let {
        it.click()
        device.waitForIdle(1_000)
    }
    val shot = File(instrumentation.targetContext.cacheDir, "send-$name-device.png")
    check(device.takeScreenshot(shot))
    writeAuditDownload("send-$name-device.png", "image/png") { out -> shot.inputStream().use { it.copyTo(out) } }
    shot.delete()
    writeAuditDownload("send-$name-device.xml", "text/xml") { out -> device.dumpWindowHierarchy(out) }
}

/** Writes a file to the device's Download/Send folder, which CI pulls after the instrumentation run. */
internal fun writeAuditDownload(fileName: String, mimeType: String, write: (java.io.OutputStream) -> Unit) {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val values = android.content.ContentValues().apply {
        put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
        put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mimeType)
        put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Download/Send")
        put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        ?: error("Could not create $fileName output.")
    try {
        (resolver.openOutputStream(uri) ?: error("Could not open $fileName output.")).use(write)
        values.clear()
        values.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
        check(resolver.update(uri, values, null, null) == 1)
    } catch (failure: Exception) {
        resolver.delete(uri, null, null)
        throw failure
    }
}
