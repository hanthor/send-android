# Test-only rules for the `minified` build type, appended to proguard-android-optimize.txt and proguard-rules.pro
# (the release configuration, unchanged). Never used by release.
#
# The instrumentation APK is a separate APK: AGP does not shrink it together with the app, it only renames its
# references with the app's mapping.txt. Whatever the tests call in the app, or in a library both APKs share (the
# test APK leaves those out and uses the app's copy), therefore has to survive R8's shrinking and inlining in the
# app. Classes reached by reflection (JNA, uniffi, RTC, WebRTC, workers) are NOT listed here: they are covered by
# proguard-rules.pro alone, which is what the suite proves.

# Send's own classes: the tests drive screens, fixtures, the repository and notification code directly.
-keep class dev.send.matrix.** { *; }

# The integration tests use the Matrix SDK directly (a second client for "bob", room setup) and one Element Call
# UI type; they reach API the app itself may not call.
-keep class org.matrix.rustcomponents.sdk.** { *; }
-keep class uniffi.** { *; }
-keep class io.element.android.call.** { *; }

# Libraries shared with the test APK (compose-ui-test, androidx.test, uiautomator and the test code call into
# them): Kotlin (Intrinsics null checks the app's R8 removes), coroutines (kotlinx-coroutines-test extends its
# internals), Compose (ui-test drives the runtime, semantics and node internals; the icon pack is not used by tests),
# Activity, Lifecycle, SavedState, Core, collections, tracing and the futures androidx.test uses.
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keep class !androidx.compose.material.icons.**,androidx.compose.** { *; }
-keep class androidx.activity.** { *; }
-keep class androidx.lifecycle.** { *; }
-keep class androidx.savedstate.** { *; }
-keep class androidx.core.** { *; }
-keep class androidx.collection.** { *; }
-keep class androidx.tracing.** { *; }
-keep class androidx.concurrent.futures.** { *; }
-keep class com.google.common.util.concurrent.ListenableFuture { *; }
