package dev.send.matrix

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith

/**
 * Store listing screenshots (fastlane `images/phoneScreenshots`): the main screens with realistic fake data on a
 * phone-sized display (1080x2400 at 420 dpi requested; the CI emulator captures 1080x1920) and a demo-mode status bar. Saved as
 * `send-store-<n>-<name>-device.png` in the `Send-UI-Screenshots` artifact.
 */
@RunWith(AndroidJUnit4::class)
class StoreScreenshotsTest {
    /** Outermost, so the display is phone-sized before the compose activity starts and restored after it ends. */
    @get:Rule(order = 0)
    val phoneDisplay = StoreDisplay(1080, 2400, 420)

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val now = System.currentTimeMillis()

    @Test
    fun signIn() {
        composeRule.setContent {
            SendTheme {
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
        composeRule.onNodeWithText("Get started").assertIsDisplayed()
        // The hero's endless orbit animation holds frames back from the display: pause the clock so
        // auditShot pumps frames itself.
        composeRule.mainClock.autoAdvance = false
        composeRule.mainClock.advanceTimeBy(2_000)
        composeRule.auditShot("store-1-sign-in")
    }

    @Test
    fun conversationList() {
        composeRule.setContent {
            SendTheme {
                HomeScreen(
                    rooms = storeRooms(now),
                    onOpen = {},
                    error = "",
                    notice = "",
                    creatingDirectRoom = false,
                    onCreateDirectRoom = {},
                    onCreateGroup = { _, _ -> },
                    onAccount = {},
                    onVerifyDevice = null,
                )
            }
        }
        composeRule.onNodeWithText("Weekend plans").assertIsDisplayed()
        composeRule.auditShot("store-2-conversations")
    }

    @Test
    fun conversation() {
        val image = storeLinkImage(context)
        composeRule.setContent { SendTheme { StoreConversation(storeConversation(now), image) } }
        waitForText("Ridge Loop Trail")
        composeRule.auditShot("store-3-conversation")
    }

    @Test
    fun groupDetails() {
        val details = RoomDetails(
            roomId = "!weekend:example.org",
            name = "Weekend plans",
            topic = "Cabin trip, 14–16 Nov",
            avatarUrl = null,
            direct = false,
            encrypted = true,
            people = listOf(
                RoomPerson("@me:example.org", "Me", null, role = PersonRole.Admin, isMe = true),
                RoomPerson("@maya:example.org", "Maya Lin", null),
                RoomPerson("@sam:example.org", "Sam", null, role = PersonRole.Moderator),
                RoomPerson("@jonas:example.org", "Jonas", null),
            ),
            notifications = NotificationLevel.All,
            canInvite = true,
            canRename = true,
            canSetTopic = true,
            canSetAvatar = true,
            dmUserId = null,
            dmIdentity = null,
        )
        val actions = ConversationDetailsActions(
            load = { details },
            setNotifications = {},
            invite = {},
            rename = {},
            setTopic = {},
            setAvatar = {},
            archive = {},
            delete = {},
            block = {},
            message = {},
        )
        composeRule.setContent {
            SendTheme {
                ConversationScreen(
                    title = details.name,
                    isDirect = false,
                    draft = "",
                    onDraft = {},
                    messages = emptyList(),
                    onBack = {},
                    onSend = {},
                    onOlder = {},
                    onReact = { _, _ -> },
                    onCall = {},
                    onSendAttachment = {},
                    onSendVoice = {},
                    loadMedia = { _, _ -> error("No media in this fixture.") },
                    uploading = false,
                    error = "",
                    decryptionActionLabel = null,
                    onDecryptionAction = {},
                    onToggleMute = {},
                    loadStarred = { emptyList() },
                    details = actions,
                )
            }
        }
        composeRule.onNodeWithContentDescription("More options").performClick()
        composeRule.onNodeWithText("Details").performClick()
        waitForText("4 people")
        composeRule.auditShot("store-4-details")
    }

    @Test
    fun settings() {
        composeRule.setContent {
            SendTheme {
                SettingsScreen(
                    notificationSummary = "Push through ntfy",
                    onNotifications = {},
                    sounds = true,
                    onSounds = {},
                    theme = ThemeChoice.System,
                    onTheme = {},
                    linkPreviews = true,
                    onLinkPreviews = {},
                    securitySummary = "Device verified · recovery on",
                    onSecurity = {},
                    onDevices = {},
                    onBlocked = {},
                    onArchived = {},
                    onAbout = {},
                    onBack = {},
                )
            }
        }
        composeRule.onNodeWithText("Choose theme").assertIsDisplayed()
        composeRule.auditShot("store-5-settings")
    }

    @Test
    fun incomingCall() {
        composeRule.setContent {
            SendTheme { IncomingCallScreen("Maya Lin", roomName = null, video = true, onAnswer = {}, onDecline = {}) }
        }
        composeRule.onNodeWithText("Maya Lin").assertIsDisplayed()
        composeRule.auditShot("store-6-incoming-call")
    }

    private fun waitForText(text: String) {
        composeRule.waitUntil(10_000) { composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }
}

/**
 * The tenInch store screenshot (fastlane `images/tenInchScreenshots`): the conversation list beside the open
 * conversation at 1280x800dp (1920x1200 at 240 dpi; the CI emulator's display tops out at 1920px a side).
 */
@RunWith(AndroidJUnit4::class)
class StoreTabletScreenshotsTest {
    @get:Rule(order = 0)
    val tabletDisplay = StoreDisplay(1920, 1200, 240)

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    private val now = System.currentTimeMillis()

    @Test
    fun listBesideConversation() {
        val image = storeLinkImage(InstrumentationRegistry.getInstrumentation().targetContext)
        val rooms = storeRooms(now)
        composeRule.setContent {
            SendTheme {
                val paneWidth = listPaneWidth()
                AdaptiveScreenHost(
                    screen = SCREEN_CONVERSATION,
                    listPaneWidth = paneWidth,
                    list = {
                        HomeScreen(
                            rooms = rooms,
                            onOpen = {},
                            error = "",
                            notice = "",
                            creatingDirectRoom = false,
                            onCreateDirectRoom = {},
                            onCreateGroup = { _, _ -> },
                            onAccount = {},
                            onVerifyDevice = null,
                            openRoomId = rooms.first().id,
                        )
                    },
                ) {
                    StoreConversation(storeConversation(now), image)
                }
            }
        }
        composeRule.waitUntil(10_000) { composeRule.onAllNodesWithText("Ridge Loop Trail").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText("Weekend plans").assertIsDisplayed()
        composeRule.auditShot("store-tablet-1-list-detail")
    }
}

/** Display size and density plus a clean demo-mode status bar for the duration of each test. */
class StoreDisplay(private val width: Int, private val height: Int, private val density: Int) : ExternalResource() {
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    override fun before() {
        device.executeShellCommand("wm size ${width}x$height")
        device.executeShellCommand("wm density $density")
        device.executeShellCommand("settings put global sysui_demo_allowed 1")
        // Demo mode keeps the real clock, so the status bar agrees with the fixtures' relative times.
        demo("enter")
        demo("battery -e level 100 -e plugged false")
        demo("network -e wifi show -e level 4 -e mobile hide")
        demo("notifications -e visible false")
        device.waitForIdle()
    }

    override fun after() {
        demo("exit")
        device.executeShellCommand("wm density reset")
        device.executeShellCommand("wm size reset")
        device.executeShellCommand("settings put global sysui_demo_allowed 0")
        device.waitForIdle()
    }

    private fun demo(command: String) {
        val (name, extras) = command.split(" ", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        device.executeShellCommand("am broadcast -a com.android.systemui.demo -e command $name $extras".trim())
    }
}

private const val MINUTE = 60_000L

private fun storeRooms(now: Long) = listOf(
    MatrixRoom("!maya:example.org", "Maya Lin", encrypted = true, direct = true, lastMessage = "Looks amazing, count me in!", lastTimestampMillis = now - 2 * MINUTE, unreadCount = 2, pinned = true),
    MatrixRoom("!weekend:example.org", "Weekend plans", encrypted = true, direct = false, lastMessage = "Sam: I'll bring the board games", lastTimestampMillis = now - 14 * MINUTE, unreadCount = 5),
    MatrixRoom("!jonas:example.org", "Jonas", encrypted = true, direct = true, lastMessage = "You: Photo", lastTimestampMillis = now - 52 * MINUTE),
    MatrixRoom("!climbing:example.org", "Climbing crew", encrypted = true, direct = false, lastMessage = "Priya: Thursday 7pm at the wall?", lastTimestampMillis = now - 3 * 60 * MINUTE),
    MatrixRoom("!mum:example.org", "Mum", encrypted = true, direct = true, lastMessage = "Voice message", lastTimestampMillis = now - 26 * 60 * MINUTE),
    MatrixRoom("!book:example.org", "Book club", encrypted = true, direct = false, lastMessage = "Ade: Next pick is up for a vote", lastTimestampMillis = now - 3 * 24 * 60 * MINUTE),
    MatrixRoom("!ravi:example.org", "Ravi", encrypted = true, direct = true, lastMessage = "Thanks for the recommendation!", lastTimestampMillis = now - 6 * 24 * 60 * MINUTE),
)

private fun storeConversation(now: Long): List<MatrixMessage> {
    fun message(id: String, text: String, own: Boolean, minutesAgo: Int, reactions: List<String> = emptyList(), replyTo: ReplyQuote? = null, readBy: Map<String, Long> = emptyMap()) =
        MatrixMessage(
            id = id,
            sender = if (own) "@me:example.org" else "@maya:example.org",
            text = text,
            own = own,
            timestampMillis = now - minutesAgo * MINUTE,
            reactions = reactions,
            senderName = if (own) null else "Maya Lin",
            replyTo = replyTo,
            editable = own,
            readBy = readBy,
        )
    val trail = "Found the trail we talked about https://example.org/trails/ridge-loop"
    return listOf(
        message("\$m1", "Are we still on for the cabin this weekend? 🏡", own = false, minutesAgo = 12),
        message("\$m2", "Yes! Leaving Friday after work", own = true, minutesAgo = 11, reactions = listOf("🎉")),
        message("\$m3", trail, own = false, minutesAgo = 4, reactions = listOf("😍")),
        message(
            "\$m4",
            "Looks amazing, count me in!",
            own = true,
            minutesAgo = 2,
            replyTo = ReplyQuote("\$m3", sender = "Maya Lin", own = false, text = trail),
            readBy = mapOf("@maya:example.org" to now),
        ),
    )
}

/** Maya's conversation with the trail link's preview card. */
@Composable
private fun StoreConversation(messages: List<MatrixMessage>, image: File) {
    ConversationScreen(
        title = "Maya Lin",
        isDirect = true,
        draft = "",
        onDraft = {},
        messages = messages,
        onBack = {},
        onSend = {},
        onOlder = {},
        onReact = { _, _ -> },
        onCall = {},
        onSendAttachment = {},
        onSendVoice = {},
        loadMedia = { _, _ -> error("No media in this fixture.") },
        uploading = false,
        error = "",
        decryptionActionLabel = null,
        onDecryptionAction = {},
        loadLinkPreview = { url ->
            LinkPreview(url, "Ridge Loop Trail", "A 9 km loop with lake views and an old fire tower at the top.", "example.org", image)
        },
    )
}

private fun storeLinkImage(context: android.content.Context): File {
    val file = File(context.cacheDir, "store-link-preview.png")
    val bitmap = Bitmap.createBitmap(1200, 630, Bitmap.Config.ARGB_8888)
    Canvas(bitmap).drawPaint(Paint().apply {
        shader = LinearGradient(0f, 0f, 1200f, 630f, 0xFF2E7D5B.toInt(), 0xFF8BC4E8.toInt(), Shader.TileMode.CLAMP)
    })
    file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    return file
}
