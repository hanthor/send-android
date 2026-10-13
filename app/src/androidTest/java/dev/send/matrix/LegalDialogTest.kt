package dev.send.matrix

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LegalDialogTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun legalDialogShowsCopyrightAndBundledNotices() {
        composeRule.setContent {
            MaterialTheme {
                LegalNoticesDialog(onDismiss = {})
            }
        }

        composeRule.onNodeWithText("About & licenses").assertIsDisplayed()
        composeRule.onNodeWithText("Copyright (C) 2026 Send contributors.", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("Element Call Android", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun signInScreenExposesAboutLicensesAction() {
        composeRule.setContent {
            MaterialTheme {
                SignInScreen(
                    homeserver = "",
                    onHomeserver = {},
                    user = "",
                    onUser = {},
                    password = "",
                    onPassword = {},
                    busy = false,
                    error = "",
                    onCancel = {},
                    ssoPending = false,
                    onCancelSso = {},
                    onQrLogin = {},
                    onSsoLogin = {},
                    onLogin = {},
                )
            }
        }

        composeRule.onNodeWithText("About & licenses").performScrollTo().performClick()
        composeRule.onNodeWithText("Copyright (C) 2026 Send contributors.", substring = true)
            .assertIsDisplayed()
    }
}
