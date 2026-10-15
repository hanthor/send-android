package dev.send.matrix

import android.app.Application
import org.matrix.rustcomponents.sdk.LogLevel
import org.matrix.rustcomponents.sdk.TracingConfiguration
import org.matrix.rustcomponents.sdk.initPlatform
import org.matrix.rustsdk.Android

class SendApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // The SDK's JNI bootstrap registers Android's platform certificate verifier.
        // It must run before initPlatform() and before any ClientBuilder is used.
        System.loadLibrary("matrix_sdk_ffi")
        Android.init(this)
        initPlatform(
            config = TracingConfiguration(
                logLevel = LogLevel.WARN,
                traceLogPacks = emptyList(),
                extraTargets = emptyList(),
                writeToStdoutOrSystem = true,
                writeToFiles = null,
                sentryConfig = null,
            ),
            useLightweightTokioRuntime = false,
        )
        // Registered up front so the "Messages" channel is in system settings before the first message.
        dev.send.matrix.notifications.MessageNotifier.ensureChannel(this)
    }
}
