package dev.send.matrix

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview

/** Static design fixtures for Android Studio's Compose Preview; they do not touch Matrix services. */
@Preview(name = "Sign-in · QR first", showBackground = true, showSystemUi = true)
@Composable
private fun SignInScreenPreview() {
    SendTheme {
        SignInScreen(
            homeserver = "https://matrix.example.org",
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

@Preview(name = "Conversation list", showBackground = true, showSystemUi = true)
@Composable
private fun HomeScreenPreview() {
    SendTheme {
        HomeScreen(
            rooms = listOf(
                MatrixRoom("!maya:example.org", "Maya", encrypted = true, direct = true),
                MatrixRoom("!studio:example.org", "Design studio", encrypted = true, direct = false),
                MatrixRoom("!jules:example.org", "Jules", encrypted = true, direct = true),
            ),
            onOpen = {},
            error = "",
            notice = "",
            creatingDirectRoom = false,
            onCreateDirectRoom = {},
        )
    }
}

@Preview(name = "Conversation", showBackground = true, showSystemUi = true)
@Composable
private fun ConversationScreenPreview() {
    SendTheme {
        ConversationScreen(
            title = "Maya",
            isDirect = true,
            draft = "",
            onDraft = {},
            messages = listOf(
                MatrixMessage(
                    id = "sample-incoming",
                    sender = "@maya:example.org",
                    text = "Made it home. Thanks for today!",
                    own = false,
                    timestampMillis = 1_746_000_000_000L,
                    reactions = listOf("❤️"),
                ),
                MatrixMessage(
                    id = "sample-own",
                    sender = "@me:example.org",
                    text = "That was lovely 😊",
                    own = true,
                    timestampMillis = 1_746_000_060_000L,
                    reactions = emptyList(),
                ),
            ),
            onBack = {},
            onSend = {},
            onOlder = {},
            onReact = { _, _ -> },
            onAudioCall = {},
            error = "",
        )
    }
}
