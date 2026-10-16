package dev.send.matrix

import androidx.activity.ComponentActivity

/**
 * Debug-only host for the large-screen fixture tests. Like [MainActivity] it keeps running through window size
 * changes (and, for the tests' `wm density`, density changes), so `wm size` acts like folding and unfolding a
 * device instead of recreating the activity. The tests set its content.
 */
class LargeScreenFixtureActivity : ComponentActivity()
