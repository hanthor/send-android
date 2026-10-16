# Release R8 rules for Send (AGP full mode, on top of proguard-android-optimize.txt).
#
# Libraries add their own consumer rules on top of these: element-call and element-call-matrix (JNA, the SDK and RTC
# bindings, libwebrtc, kotlinx.serialization), matrix-rtc-android (JNA, org.matrix.rtc, libwebrtc), WorkManager,
# AndroidX and kotlinx. sdk-android and jna ship none. Send repeats the rules its own code depends on so that
# dropping or upgrading one of those libraries can't silently break the Matrix SDK. Every rule says why it exists;
# release.yml runs the whole instrumentation suite on the `minified` build type, which uses this file unchanged.

# --- JNA (net.java.dev.jna:jna; the uniffi bindings of matrix-rust-sdk and matrix-rust-rtc call native code through it)
# libjnidispatch.so looks up com.sun.jna classes, fields (Pointer.peer, Structure internals) and methods
# (Native.fromNative, Structure.newInstance, CallbackReference, ...) by name over JNI.
-keep class com.sun.jna.** { *; }
# JNA subtypes in the bindings, all used reflectively:
# - Structure (RustBuffer, ForeignBytes, UniffiRustCallStatus, UniffiVTableCallbackInterface*, UniffiForeignFuture*)
#   and their ByValue/ByReference variants: JNA reads the public fields by the names in @Structure.FieldOrder and
#   creates instances through the no-arg constructor (including ByValue return values of native calls);
# - Callback (UniffiCallbackInterface*, UniffiRustFutureContinuationCallback, ...): JNA calls the interface's
#   `callback` method, found by reflection, when Rust invokes a foreign callback (timeline, sync, room-list
#   listeners, async continuations);
# - Library interfaces.
-keep class * implements com.sun.jna.** { *; }
# Structure.getFieldOrder() reads the @Structure.FieldOrder annotation at run time.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

# --- matrix-rust-sdk bindings (org.matrix.rustcomponents:sdk-android: org.matrix.rustcomponents.sdk and uniffi.*)
# Native.register() binds the `native` methods of UniffiLib and IntegrityCheckingUniffiLib to the exported C symbols
# by method name and parameter types, and the generated code checks the uniffi contract against those symbols.
# Keep the names; R8 may still drop code Send never reaches. Same rule as Element X (app/proguard/common).
# (element-call-matrix's consumer rules currently keep these packages in full.)
-keep,allowoptimization,allowshrinking class org.matrix.rustcomponents.sdk.** { *; }
-keep,allowoptimization,allowshrinking class uniffi.** { *; }

# --- Element Call (io.element.android:element-call*, io.element.android:matrix-rtc-android)
# The RTC core's uniffi records, enums and callback interfaces are resolved by name from Rust (mirrors the rules
# matrix-rtc-android and element-call ship).
-keep class org.matrix.rtc.** { *; }
# libwebrtc's Java half (bundled in matrix-rtc-android) and its jni_zero glue: native code calls these classes and
# methods by name, which R8 cannot see.
-keep class livekit.org.webrtc.** { *; }
-keep class livekit.org.jni_zero.** { *; }

# --- WorkManager workers (BackgroundSyncWorker, PushEventWorker, NotificationActionWorker)
# WorkManager stores the worker's class name in its database and creates it by reflection through the
# (Context, WorkerParameters) constructor, possibly after an app update (the periodic BackgroundSyncWorker survives
# updates), so the names must stay the same from one release to the next, not just within one build.
-keep class dev.send.matrix.** extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# Nothing else in Send needs a rule:
# - Manifest components (activities, SendPushService, NotificationActionReceiver, the UnifiedPush connector's and
#   zxing's own components, FileProvider, androidx.startup initializers) are kept from the merged manifest.
# - UnifiedPush connector: Tink's subtle EC/HKDF classes are called directly; no reflection.
# - zxing-android-embedded, emoji2/emoji2-emojipicker: no reflection; the picker's emoji data are raw resources the
#   resource shrinker traces from code.
# - kotlinx.serialization: Send has no @Serializable types; the library and Element Call ship their rules.
