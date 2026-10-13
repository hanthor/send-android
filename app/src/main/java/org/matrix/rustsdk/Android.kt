package org.matrix.rustsdk

import android.content.Context

/** JNI entry point required by Matrix Rust SDK on Android before initPlatform(). */
object Android {
    @JvmStatic
    external fun init(context: Context)
}
