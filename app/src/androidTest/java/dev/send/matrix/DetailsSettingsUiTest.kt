package dev.send.matrix

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.media.AudioManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 6 on fixtures: conversation details for a DM and a group (notifications, people with the profile sheet, Add
 * people, rename, media grid, block/delete), the profile page, Settings (theme, previews, sounds) with its dark
 * variant, the Devices page with password-confirmed sign-out, and the in-conversation sounds. Each visual state is
 * saved with [auditShot] for the Google Messages comparison.
 */
@RunWith(AndroidJUnit4::class)
class DetailsSettingsUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val now = System.currentTimeMillis()

    private fun message(id: String, text: String, own: Boolean, minutesAgo: Int, attachment: MatrixAttachment? = null, status: DeliveryStatus = DeliveryStatus.Sent) =
        MatrixMessage(
            id = id,
            sender = if (own) "@me:example.org" else "@alice:example.org",
            text = text,
            own = own,
            timestampMillis = now - minutesAgo * 60_000L,
            reactions = emptyList(),
            attachment = attachment,
            status = status,
            senderName = if (own) null else "Alice",
        )

    private val chat = listOf(
        message("\$a1", "Are we still on for dinner tonight?", own = false, minutesAgo = 9),
        message("\$m1", "Yes! Dinner at 7 works for me", own = true, minutesAgo = 8),
        message("\$a2", "Great, I'll book the table", own = false, minutesAgo = 7),
    )

    private val dmDetails = RoomDetails(
        roomId = "!alice:example.org",
        name = "Alice",
        topic = null,
        avatarUrl = null,
        direct = true,
        encrypted = true,
        people = listOf(
            RoomPerson("@me:example.org", "Me", null, isMe = true),
            RoomPerson("@alice:example.org", "Alice", null),
        ),
        notifications = NotificationLevel.All,
        canInvite = true,
        canRename = true,
        canSetTopic = true,
        canSetAvatar = true,
        dmUserId = "@alice:example.org",
        dmIdentity = IdentityState.Verified,
    )

    private val groupDetails = RoomDetails(
        roomId = "!weekend:example.org",
        name = "Weekend plans",
        topic = "Cabin trip, 14–16 Nov",
        avatarUrl = null,
        direct = false,
        encrypted = true,
        people = listOf(
            RoomPerson("@me:example.org", "Me", null, role = PersonRole.Admin, isMe = true),
            RoomPerson("@alice:example.org", "Alice", null),
            RoomPerson("@sam:example.org", "Sam", null, role = PersonRole.Moderator),
            RoomPerson("@robin:example.org", null, null, invited = true),
        ),
        notifications = NotificationLevel.Mentions,
        canInvite = true,
        canRename = true,
        canSetTopic = true,
        canSetAvatar = true,
        dmUserId = null,
        dmIdentity = null,
    )

    /** Records every details action and applies it to the details the next load returns. */
    private class FakeDetails(initial: RoomDetails) {
        @Volatile var details = initial
        val calls = java.util.Collections.synchronizedList(mutableListOf<String>())
        val actions = ConversationDetailsActions(
            load = { details },
            setNotifications = { level -> calls += "notify:$level"; details = details.copy(notifications = level) },
            invite = { userId ->
                calls += "invite:$userId"
                details = details.copy(people = details.people + RoomPerson(userId, null, null, invited = true))
            },
            rename = { name -> calls += "rename:$name"; details = details.copy(name = name) },
            setTopic = { topic -> calls += "topic:$topic"; details = details.copy(topic = topic) },
            setAvatar = { calls += "avatar" },
            archive = { calls += "archive" },
            delete = { calls += "delete" },
            block = { userId -> calls += "block:$userId" },
            message = { userId -> calls += "message:$userId" },
        )
    }

    private fun showConversation(
        fake: FakeDetails,
        messages: List<MatrixMessage> = chat,
        loadMedia: MediaLoader = { _, _ -> error("No media in this fixture.") },
    ) {
        composeRule.setContent {
            SendTheme {
                ConversationScreen(
                    title = fake.details.name,
                    isDirect = fake.details.direct,
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
                    loadMedia = loadMedia,
                    uploading = false,
                    error = "",
                    decryptionActionLabel = null,
                    onDecryptionAction = {},
                    onToggleMute = {},
                    loadStarred = { emptyList() },
                    details = fake.actions,
                )
            }
        }
    }

    private fun waitForText(text: String, timeoutMillis: Long = 10_000) {
        composeRule.waitUntil(timeoutMillis) { composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    /** Taps the clickable row showing [row] until [expected] appears (a tap landing while the page settles is retried). */
    private fun tapUntilShown(row: String, expected: String) {
        for (attempt in 1..3) {
            scrollDetailsTo(row)
            composeRule.onNode(hasText(row) and hasClickAction()).performClick()
            val shown = runCatching { waitForText(expected, 4_000) }.isSuccess
            if (shown) return
        }
        throw AssertionError("\"$expected\" didn't appear after tapping \"$row\" 3 times")
    }

    private fun scrollDetailsTo(text: String) {
        composeRule.onNodeWithTag(DETAILS_LIST_TAG).performScrollToNode(hasText(text))
    }

    @Test
    fun dmDetailsShowIdentityNotificationsAndBlock() {
        val fake = FakeDetails(dmDetails)
        showConversation(fake)
        // Messages: tapping the contact in the header opens the details.
        composeRule.onNode(hasText("Alice") and hasClickAction()).performClick()
        // The header (top of the page) fills in once details load; lower rows compose as they scroll in.
        waitForText("@alice:example.org")
        // The address sits under the name and again on the "1 other person" card's row (Messages' layout).
        composeRule.onAllNodesWithText("@alice:example.org")[0].assertIsDisplayed()
        composeRule.onNodeWithText("1 other person").assertExists()
        composeRule.onNodeWithText("Search").assertIsDisplayed()
        composeRule.onNodeWithText("Mute").assertIsDisplayed()
        composeRule.auditShot("details-dm")
        scrollDetailsTo("You verified Alice's identity")
        composeRule.onNodeWithText("You verified Alice's identity").assertIsDisplayed()

        scrollDetailsTo("Notifications")
        composeRule.onNodeWithText("Notifications").performClick()
        waitForText("Mentions only")
        composeRule.auditShot("details-notifications")
        composeRule.onNodeWithText("Mentions only").performClick()
        composeRule.onNodeWithText("OK").performClick()
        composeRule.waitUntil(10_000) { "notify:Mentions" in fake.calls }
        // The page reloads from the (fake) server: the row now reads Mentions only.
        waitForText("Mentions only")

        scrollDetailsTo("Delete conversation")
        composeRule.onNodeWithText("Block").performClick()
        composeRule.onNodeWithText("Block Alice?").assertIsDisplayed()
        composeRule.onNodeWithText("Yes").performClick()
        composeRule.runOnIdle { assertTrue(fake.calls.toString(), "block:@alice:example.org" in fake.calls) }
        composeRule.onNodeWithText("Delete conversation").performClick()
        composeRule.onNodeWithText("Delete this conversation?").assertIsDisplayed()
        composeRule.onNodeWithText("Delete").performClick()
        composeRule.runOnIdle { assertTrue(fake.calls.toString(), "delete" in fake.calls) }
    }

    @Test
    fun groupDetailsListPeopleAndChangeTheGroup() {
        val fake = FakeDetails(groupDetails)
        showConversation(fake)
        composeRule.onNodeWithContentDescription("More options").performClick()
        composeRule.onNodeWithText("Details").performClick()
        waitForText("4 people")
        composeRule.onAllNodesWithText("Cabin trip, 14–16 Nov")[0].assertIsDisplayed()
        composeRule.auditShot("details-group")

        // Rename through the Group name row.
        scrollDetailsTo("Group name")
        composeRule.onNodeWithText("Group name").performClick()
        composeRule.onNodeWithTag(DETAILS_TEXT_INPUT_TAG).performTextReplacement("Cabin weekend")
        composeRule.onNodeWithText("Save").performClick()
        composeRule.waitUntil(10_000) { "rename:Cabin weekend" in fake.calls }
        // The page reloads with the new name.
        waitForText("Cabin weekend")

        // People: everyone with their role, the invitee marked Invited.
        scrollDetailsTo("@robin:example.org")
        composeRule.onNodeWithText("You").assertExists()
        composeRule.onNodeWithText("@sam:example.org · Moderator").assertExists()
        composeRule.onNodeWithText("Invited").assertExists()

        // Add people: invite by Matrix ID.
        scrollDetailsTo("Add people")
        composeRule.onNodeWithText("Add people").performClick()
        composeRule.onNodeWithTag(ADD_PEOPLE_INPUT_TAG).performTextInput("@carol:example.org")
        composeRule.auditShot("add-people")
        composeRule.onNodeWithText("Invite").performClick()
        composeRule.waitUntil(10_000) { "invite:@carol:example.org" in fake.calls }
        composeRule.waitUntil(10_000) { composeRule.onAllNodesWithText("Invited").fetchSemanticsNodes().size >= 2 }

        // A member's profile sheet: Message opens their DM.
        scrollDetailsTo("Sam")
        composeRule.onNodeWithText("Sam").performClick()
        composeRule.waitUntil(10_000) { composeRule.onAllNodesWithTag(PERSON_SHEET_TAG).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNode(hasText("Block") and hasAnyAncestor(hasTestTag(PERSON_SHEET_TAG))).assertIsDisplayed()
        composeRule.auditShot("people-sheet")
        composeRule.onNode(hasText("Message") and hasAnyAncestor(hasTestTag(PERSON_SHEET_TAG))).performClick()
        composeRule.waitUntil(10_000) { "message:@sam:example.org" in fake.calls }

        // The sheet closes first; then Leave group.
        composeRule.waitUntil(10_000) { composeRule.onAllNodesWithTag(PERSON_SHEET_TAG).fetchSemanticsNodes().isEmpty() }
        tapUntilShown("Leave group", "Leave this group?")
        composeRule.onNodeWithText("Leave").performClick()
        composeRule.runOnIdle { assertTrue(fake.calls.toString(), "delete" in fake.calls) }
    }

    @Test
    fun mediaGridShowsThumbnailsFromTheLoadedTimeline() {
        val colors = listOf(0xFF1A73E8 to 0xFFF9AB00, 0xFF34A853 to 0xFF1A73E8, 0xFFEA4335 to 0xFFFBBC04, 0xFF9334E6 to 0xFF4285F4, 0xFF00897B to 0xFFFFCC80)
        val images = colors.mapIndexed { index, (from, to) -> fixtureImage("details-media-$index.png", from.toInt(), to.toInt()) }
        val photos = images.mapIndexed { index, file ->
            message(
                "\$photo$index",
                "",
                own = index % 2 == 0,
                minutesAgo = 30 - index,
                attachment = MatrixAttachment(
                    kind = AttachmentKind.Image,
                    filename = file.name,
                    mimetype = "image/png",
                    sizeBytes = file.length(),
                    durationMillis = null,
                    width = 1200,
                    height = 800,
                    sourceJson = "{\"url\":\"mxc://example.org/details-$index\"}",
                    thumbnailSourceJson = null,
                ),
            )
        }
        val document = message(
            "\$file",
            "",
            own = false,
            minutesAgo = 2,
            attachment = MatrixAttachment(AttachmentKind.File, "Itinerary.pdf", "application/pdf", 52_000, null, null, null, "{\"url\":\"mxc://example.org/pdf\"}", null),
        )
        val byName = images.associateBy { it.name }
        showConversation(FakeDetails(groupDetails), messages = chat + photos + document, loadMedia = { _, name -> byName.getValue(name) })
        composeRule.onNodeWithContentDescription("More options").performClick()
        composeRule.onNodeWithText("Details").performClick()
        waitForText("4 people")
        scrollDetailsTo("Media & files")
        waitForText("6 items")
        // The row and its thumbnail strip are one tappable card; tap the row itself (a thumbnail would open the viewer).
        composeRule.onNodeWithText("6 items").performTouchInput { click(Offset(center.x, 24f)) }
        composeRule.waitUntil(10_000) { composeRule.onAllNodesWithTag(MEDIA_GRID_TAG).fetchSemanticsNodes().isNotEmpty() }
        // The details strip (four newest) stays beneath the grid (all six).
        composeRule.waitUntil(10_000) { composeRule.onAllNodesWithText("Itinerary.pdf").fetchSemanticsNodes().size >= 2 }
        composeRule.waitUntil(10_000) { composeRule.onAllNodesWithContentDescription("Photo").fetchSemanticsNodes().size >= 8 }
        // Let every thumbnail decode before the shot.
        Thread.sleep(1_500)
        composeRule.auditShot("media-grid")
    }

    @Test
    fun profilePageChangesNameAndCopiesTheMatrixId() {
        val names = mutableListOf<String>()
        composeRule.setContent {
            SendTheme {
                ProfileScreen(
                    userId = "@alice:example.org",
                    profile = OwnProfile("Alice", null),
                    setName = { names += it },
                    setAvatar = {},
                    removeAvatar = {},
                    onBack = {},
                )
            }
        }
        composeRule.onNodeWithText("Matrix ID").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Change profile photo").assertIsDisplayed()
        composeRule.auditShot("profile")
        composeRule.onNodeWithTag(PROFILE_NAME_TAG).performTextReplacement("Alice Liddell")
        composeRule.onNodeWithText("Save").performClick()
        composeRule.waitUntil(10_000) { names.isNotEmpty() }
        composeRule.runOnIdle { assertEquals(listOf("Alice Liddell"), names) }
        composeRule.onNodeWithContentDescription("Copy Matrix ID").performClick()
        composeRule.runOnIdle {
            assertEquals("@alice:example.org", context.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString())
        }
    }

    private fun showSettings(theme: ThemeChoice?, picked: MutableList<String>) {
        composeRule.setContent {
            SendTheme(theme = theme) {
                SettingsScreen(
                    notificationSummary = "Background sync every 15 minutes",
                    onNotifications = { picked += "notifications" },
                    sounds = true,
                    onSounds = { picked += "sounds:$it" },
                    theme = theme ?: ThemeChoice.System,
                    onTheme = { picked += "theme:$it" },
                    linkPreviews = true,
                    onLinkPreviews = { picked += "previews:$it" },
                    securitySummary = "Device verified · recovery on",
                    onSecurity = { picked += "security" },
                    onDevices = { picked += "devices" },
                    onBlocked = { picked += "blocked" },
                    onArchived = { picked += "archived" },
                    onAbout = { picked += "about" },
                    onBack = {},
                )
            }
        }
    }

    @Test
    fun settingsOfferThemePreviewsAndSounds() {
        val picked = mutableListOf<String>()
        showSettings(theme = ThemeChoice.Light, picked = picked)
        composeRule.onNodeWithText("Hear outgoing and incoming message sounds").assertIsDisplayed()
        composeRule.auditShot("settings")
        composeRule.onNodeWithText("Hear outgoing and incoming message sounds").performClick()
        composeRule.onNodeWithText("Choose theme").performClick()
        waitForText("System default")
        composeRule.auditShot("settings-theme")
        composeRule.onNodeWithText("Dark").performClick()
        composeRule.onNodeWithText("OK").performClick()
        composeRule.onNodeWithText("Automatic previews").performClick()
        waitForText("Previews are fetched by your homeserver, which then learns the links in your messages.")
        composeRule.auditShot("settings-previews")
        composeRule.onNode(hasText("Show web link previews") and hasClickAction()).performClick()
        composeRule.onNodeWithContentDescription("Back").performClick()
        waitForText("Choose theme")
        composeRule.onNodeWithText("Devices").performScrollTo().performClick()
        composeRule.onNodeWithText("About & licenses").performScrollTo().performClick()
        composeRule.runOnIdle {
            assertEquals(listOf("sounds:false", "theme:Dark", "previews:false", "devices", "about"), picked)
        }
    }

    @Test
    fun settingsDarkVariant() {
        showSettings(theme = ThemeChoice.Dark, picked = mutableListOf())
        composeRule.onNodeWithText("Choose theme").assertIsDisplayed()
        composeRule.auditShot("settings-dark")
    }

    @Test
    fun chosenThemeIsSavedAndAppliedWithoutAnOverride() {
        val settings = SendSettings.get(context)
        var surface = Color.Unspecified
        composeRule.setContent { SendTheme { surface = MaterialTheme.colorScheme.surface } }
        try {
            settings.setTheme(ThemeChoice.Dark)
            composeRule.waitUntil(5_000) { surface == Color(0xFF111318) }
            settings.setTheme(ThemeChoice.Light)
            composeRule.waitUntil(5_000) { surface == Color(0xFFF9F9FF) }
            assertEquals("Light", context.getSharedPreferences("send_settings", Context.MODE_PRIVATE).getString("theme", null))
        } finally {
            settings.setTheme(ThemeChoice.System)
        }
    }

    @Test
    fun devicesPageSignsAnotherSessionOutWithThePassword() {
        val devices = mutableListOf(
            SessionDevice("THISPHONE", "Send on Pixel 8", now, "10.0.2.16", current = true),
            SessionDevice("LAPTOP01", "Element Desktop", now - 3 * 3_600_000L, "192.0.2.10", current = false),
            SessionDevice("TABLET02", "Send on Pixel Tablet", now - 26 * 3_600_000L, "192.0.2.11", current = false),
        )
        val attempts = mutableListOf<String>()
        composeRule.setContent {
            SendTheme {
                DevicesScreen(
                    load = { devices.toList() },
                    signOut = { deviceId, password, session ->
                        attempts += "$deviceId:$password:$session"
                        when (password) {
                            null -> DeviceSignOut.Password("uia-1", wrongPassword = false)
                            "wrong" -> DeviceSignOut.Password("uia-1", wrongPassword = true)
                            else -> DeviceSignOut.Done.also { devices.removeAll { it.id == deviceId } }
                        }
                    },
                    onBack = {},
                )
            }
        }
        waitForText("Element Desktop")
        composeRule.onNodeWithText("This device").assertIsDisplayed()
        composeRule.onNodeWithText("Send on Pixel 8").assertIsDisplayed()
        composeRule.auditShot("devices")
        composeRule.onAllNodesWithText("Sign out")[0].performClick()
        composeRule.onNodeWithText("Sign out of Element Desktop?").assertIsDisplayed()
        composeRule.onAllNodesWithText("Sign out").let { it[it.fetchSemanticsNodes().size - 1] }.performClick()
        waitForText("Confirm it's you")
        composeRule.onNodeWithTag(DEVICE_PASSWORD_TAG).performTextInput("wrong")
        composeRule.onAllNodesWithText("Sign out").let { it[it.fetchSemanticsNodes().size - 1] }.performClick()
        waitForText("That password isn't right.")
        composeRule.onNodeWithTag(DEVICE_PASSWORD_TAG).performTextReplacement("right")
        composeRule.onAllNodesWithText("Sign out").let { it[it.fetchSemanticsNodes().size - 1] }.performClick()
        composeRule.waitUntil(10_000) { composeRule.onAllNodesWithText("Element Desktop").fetchSemanticsNodes().isEmpty() }
        composeRule.onNodeWithText("Send on Pixel Tablet").assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(listOf("LAPTOP01:null:null", "LAPTOP01:wrong:uia-1", "LAPTOP01:right:uia-1"), attempts)
        }
    }

    @Test
    fun conversationPlaysSentAndReceivedSounds() {
        assumeTrue(
            "Sounds are silent unless the ringer is on",
            context.getSystemService(AudioManager::class.java).ringerMode == AudioManager.RINGER_MODE_NORMAL,
        )
        val messages = mutableStateOf(chat)
        composeRule.setContent {
            SendTheme {
                ConversationScreen(
                    title = "Alice",
                    isDirect = true,
                    draft = "",
                    onDraft = {},
                    messages = messages.value,
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
                    sounds = true,
                )
            }
        }
        composeRule.waitForIdle()
        val received = ConversationSounds.played[ConversationSounds.Kind.Received] ?: 0
        val sent = ConversationSounds.played[ConversationSounds.Kind.Sent] ?: 0
        // Older history paged in stays silent.
        messages.value = listOf(message("\$old", "Earlier", own = false, minutesAgo = 600)) + messages.value
        composeRule.waitForIdle()
        assertEquals(received, ConversationSounds.played[ConversationSounds.Kind.Received] ?: 0)
        messages.value = messages.value + message("\$a3", "Table for two at 7", own = false, minutesAgo = 0)
        composeRule.waitUntil(5_000) { (ConversationSounds.played[ConversationSounds.Kind.Received] ?: 0) > received }
        // An own message plays once it is sent, not while it is sending.
        messages.value = messages.value + message("txn1", "Perfect", own = true, minutesAgo = 0, status = DeliveryStatus.Sending)
        composeRule.waitForIdle()
        assertEquals(sent, ConversationSounds.played[ConversationSounds.Kind.Sent] ?: 0)
        messages.value = messages.value.dropLast(1) + message("\$m2", "Perfect", own = true, minutesAgo = 0)
        composeRule.waitUntil(5_000) { (ConversationSounds.played[ConversationSounds.Kind.Sent] ?: 0) > sent }
    }

    private fun fixtureImage(name: String, from: Int, to: Int): File {
        val file = File(context.cacheDir, name)
        val bitmap = Bitmap.createBitmap(600, 400, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, 600f, 400f, from, to, Shader.TileMode.CLAMP) })
        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        return file
    }
}
