package dev.send.matrix

import androidx.activity.ComponentActivity

/**
 * Test-only host for the large-screen fixture tests in the `minified` test build (the debug copy is in src/debug).
 * Like [MainActivity] it keeps running through window size and density changes, so `wm size` acts like folding
 * and unfolding a device instead of recreating the activity. The tests set its content.
 */
class LargeScreenFixtureActivity : ComponentActivity()
