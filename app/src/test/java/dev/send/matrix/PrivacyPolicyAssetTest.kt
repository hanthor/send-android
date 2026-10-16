package dev.send.matrix

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/** The in-app privacy policy (About & licenses → Privacy policy) must be the repository's PRIVACY.md. */
class PrivacyPolicyAssetTest {
    @Test
    fun bundledPolicyMatchesTheRepositoryCopy() {
        // Unit tests run in the app module's directory.
        val root = File("../PRIVACY.md")
        val asset = File("src/main/assets/PRIVACY.md")
        assertEquals(
            "app/src/main/assets/PRIVACY.md differs from PRIVACY.md: copy the root file over it",
            root.readText(),
            asset.readText(),
        )
    }
}
