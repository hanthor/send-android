package dev.send.matrix

import android.content.ClipboardManager
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 5 conversation list management on fixtures: swipe to archive with Undo, the selection bar (pin, archive,
 * delete, mute, read/unread, block) and its confirmations, the Archived and Spam & blocked screens, starred
 * messages and message multi-select. Each visual state is saved with [auditShot] for the Messages comparison.
 */
@RunWith(AndroidJUnit4::class)
class ListManagementUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val now = System.currentTimeMillis()
    private val minute = 60_000L

    private val fixtureRooms = listOf(
        MatrixRoom("!maya:example.org", "Maya", encrypted = true, direct = true, lastMessage = "Made it home. Thanks for today!", lastTimestampMillis = now - 5 * minute, unreadCount = 2, dmUserId = "@maya:example.org"),
        MatrixRoom("!studio:example.org", "Design studio", encrypted = true, direct = false, lastMessage = "You: Photo", lastTimestampMillis = now - 60 * minute),
        MatrixRoom("!sam:example.org", "Sam", encrypted = true, direct = true, lastMessage = "Lunch tomorrow?", lastTimestampMillis = now - 180 * minute, markedUnread = true, dmUserId = "@sam:example.org"),
        MatrixRoom("!family:example.org", "Family", encrypted = true, direct = false, lastMessage = "Mum: See you Sunday", lastTimestampMillis = now - 1_440 * minute, pinned = true),
        MatrixRoom("!club:example.org", "Book club", encrypted = true, direct = false, lastMessage = "Next month: Dune", lastTimestampMillis = now - 2_880 * minute, archived = true),
    )

    /** Records every list action and applies it to [rooms], as the repository's optimistic state would. */
    private class Calls(initial: List<MatrixRoom>) {
        val rooms = mutableStateOf(initial)
        val muted = mutableStateOf(emptySet<String>())
        val blocked = mutableStateOf(emptySet<String>())
        val archived = mutableListOf<Pair<List<String>, Boolean>>()
        val pinned = mutableListOf<Pair<List<String>, Boolean>>()
        val unread = mutableListOf<Pair<List<String>, Boolean>>()
        val mutedCalls = mutableListOf<Pair<List<String>, Boolean>>()
        val deleted = mutableListOf<List<String>>()
        val blockedCalls = mutableListOf<String>()
        val reported = mutableListOf<String>()

        private fun update(ids: List<String>, change: (MatrixRoom) -> MatrixRoom) {
            rooms.value = rooms.value.map { if (it.id in ids) change(it) else it }
        }

        val actions = ConversationListActions(
            setArchived = { picked, value ->
                val ids = picked.map { it.id }
                archived += ids to value
                update(ids) { it.copy(archived = value, pinned = if (value) false else it.pinned) }
            },
            setPinned = { picked, value ->
                val ids = picked.map { it.id }
                pinned += ids to value
                update(ids) { it.copy(pinned = value) }
            },
            setUnread = { picked, value ->
                val ids = picked.map { it.id }
                unread += ids to value
                update(ids) { it.copy(markedUnread = value, unreadCount = if (value) it.unreadCount else 0) }
            },
            setMuted = { picked, value ->
                val ids = picked.map { it.id }
                mutedCalls += ids to value
                muted.value = if (value) muted.value + ids else muted.value - ids.toSet()
            },
            delete = { picked ->
                deleted += picked.map { it.id }
                rooms.value = rooms.value.filter { room -> picked.none { it.id == room.id } }
            },
            block = { userId ->
                blockedCalls += userId
                blocked.value = blocked.value + userId
            },
            reportSpam = { room ->
                reported += room.id
                blocked.value = blocked.value + checkNotNull(room.dmUserId)
            },
        )
    }

    private fun showHome(calls: Calls, swipeActions: SwipeActions = SwipeActions()) {
        composeRule.setContent {
            SendTheme {
                HomeScreen(
                    rooms = calls.rooms.value,
                    onOpen = {},
                    error = "",
                    notice = "",
                    creatingDirectRoom = false,
                    onCreateDirectRoom = {},
                    onCreateGroup = { _, _ -> },
                    onAccount = {},
                    onVerifyDevice = null,
                    mutedRoomIds = calls.muted.value,
                    blockedUserIds = calls.blocked.value,
                    listActions = calls.actions,
                    swipeActions = swipeActions,
                )
            }
        }
    }

    @Test
    fun swipeActionsFollowTheSettingAndReportSpamBlocksAfterConfirming() {
        val calls = Calls(fixtureRooms)
        showHome(calls, SwipeActions(start = SwipeAction.MarkRead, end = SwipeAction.Delete))
        val width = context.resources.displayMetrics.widthPixels.toFloat()

        // Swipe right with "Mark as read or unread": the unread DM is marked read and the row stays.
        composeRule.onNodeWithText("Maya").performTouchInput {
            down(center)
            repeat(12) { moveBy(Offset(width * 0.045f, 0f)) }
        }
        composeRule.onNodeWithContentDescription("Mark as read").assertIsDisplayed()
        composeRule.auditShot("swipe-mark-read")
        composeRule.onNodeWithText("Maya").performTouchInput { up() }
        composeRule.waitUntil(5_000) { calls.unread.isNotEmpty() }
        assertEquals(listOf("!maya:example.org") to false, calls.unread.single())
        composeRule.onNodeWithText("Maya").assertIsDisplayed()
        assertTrue(calls.archived.isEmpty())

        // Swipe left with "Delete": the row springs back and the delete confirmation asks first.
        composeRule.onNodeWithText("Design studio").performTouchInput {
            swipe(start = center, end = Offset(centerX - width * 0.6f, centerY), durationMillis = 300)
        }
        composeRule.waitUntil(5_000) { hasText("Delete 1 conversation?") }
        composeRule.auditShot("swipe-delete-confirm")
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.onNodeWithText("Design studio").assertIsDisplayed()
        assertTrue(calls.deleted.isEmpty())

        // Report spam on a DM: confirmation, then the report and the block.
        longPress("Sam")
        composeRule.onNodeWithContentDescription("More options").performClick()
        composeRule.onNodeWithText("Report spam").performClick()
        composeRule.waitUntil(5_000) { hasText("Report spam?") }
        composeRule.onNodeWithText("Unblock them any time from Spam & blocked", substring = true).assertIsDisplayed()
        composeRule.auditShot("report-spam-confirm")
        composeRule.onNodeWithText("Report").performClick()
        composeRule.runOnIdle { assertEquals(listOf("!sam:example.org"), calls.reported) }
        composeRule.waitUntil(5_000) { !hasText("Sam") }
    }

    @Test
    fun trashListsDeletedConversationsWithRestoreDeleteForeverAndEmpty() {
        val restored = mutableListOf<List<String>>()
        val deleted = mutableListOf<List<String>>()
        val trashedRooms = fixtureRooms.filter { it.name == "Maya" || it.name == "Design studio" }
        val trashed = mapOf(
            "!maya:example.org" to TrashEntry(trashedAtMillis = now - 2 * 24 * 60 * minute, wasMuted = false),
            "!studio:example.org" to TrashEntry(trashedAtMillis = now - 60 * minute, wasMuted = true),
        )
        composeRule.setContent {
            SendTheme {
                TrashScreen(
                    rooms = trashedConversations(fixtureRooms, trashed),
                    trashed = trashed,
                    mutedRoomIds = setOf("!studio:example.org"),
                    onRestore = { restored += it.map { room -> room.id } },
                    onDeleteForever = { deleted += it.map { room -> room.id } },
                    onBack = {},
                )
            }
        }
        // Most recently deleted first, each with when it was deleted and Restore; the other rooms stay out.
        assertTrue(top("Design studio") < top("Maya"))
        composeRule.onNodeWithText("Deleted 2 days ago").assertIsDisplayed()
        composeRule.onAllNodesWithText("Family").assertCountEquals(0)
        composeRule.auditShot("trash")
        composeRule.onAllNodesWithText("Restore")[0].performClick()
        composeRule.runOnIdle { assertEquals(listOf(listOf("!studio:example.org")), restored) }

        // Long-press: Restore and Delete forever for the selection, with a confirmation that says it is for good.
        longPress("Maya")
        composeRule.onNodeWithContentDescription("Delete forever").performClick()
        composeRule.onNodeWithText("Delete 1 conversation?").assertIsDisplayed()
        composeRule.onNodeWithText("You'll leave this conversation for good", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Delete").performClick()
        composeRule.runOnIdle { assertEquals(listOf(listOf("!maya:example.org")), deleted) }

        // ⋮ → Empty Trash deletes everything in it for good, after confirming.
        composeRule.onNodeWithContentDescription("More options").performClick()
        composeRule.onNodeWithText("Empty Trash").performClick()
        composeRule.onNodeWithText("Empty Trash?").assertIsDisplayed()
        composeRule.onAllNodesWithText("Empty Trash").onLast().performClick()
        composeRule.runOnIdle { assertEquals(trashedRooms.map { it.id }.sorted(), deleted.last().sorted()) }
    }

    @Test
    fun swipeActionsPageOffersAChoicePerDirection() {
        val chosen = mutableStateOf(SwipeActions())
        composeRule.setContent {
            SendTheme {
                SwipeActionsPage(actions = chosen.value, onChange = { chosen.value = it }, onBack = {})
            }
        }
        composeRule.onNodeWithText("Swipe right").assertIsDisplayed()
        composeRule.onNodeWithText("Swipe left").assertIsDisplayed()
        composeRule.onAllNodesWithText("Archive").assertCountEquals(2)
        composeRule.onAllNodesWithText("Delete")[1].performClick()
        composeRule.runOnIdle { assertEquals(SwipeActions(start = SwipeAction.Archive, end = SwipeAction.Delete), chosen.value) }
        composeRule.onAllNodesWithText("None")[0].performClick()
        composeRule.runOnIdle { assertEquals(SwipeActions(start = SwipeAction.None, end = SwipeAction.Delete), chosen.value) }
        composeRule.auditShot("swipe-actions-settings")
    }

    private fun top(text: String): Float = composeRule.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.top

    private fun longPress(text: String) {
        composeRule.onNodeWithText(text).performTouchInput { longClick() }
        composeRule.waitForIdle()
    }

    private fun hasText(text: String) = composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun swipeArchivesWithUndoAndPinnedRowsLead() {
        val calls = Calls(fixtureRooms)
        showHome(calls)

        // Pinned first with its pin, archived out of the list, marked-unread dot, count badge.
        assertTrue(top("Family") < top("Maya"))
        composeRule.onNodeWithContentDescription("Pinned").assertIsDisplayed()
        composeRule.onAllNodesWithText("Book club").assertCountEquals(0)
        composeRule.onNodeWithContentDescription("Marked unread").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("2 unread").assertIsDisplayed()

        // Mid-swipe: the row follows the finger over the green archive track.
        val width = context.resources.displayMetrics.widthPixels.toFloat()
        composeRule.onNodeWithText("Maya").performTouchInput {
            down(center)
            repeat(12) { moveBy(Offset(width * 0.045f, 0f)) }
        }
        composeRule.onNodeWithContentDescription("Archive").assertIsDisplayed()
        composeRule.auditShot("swipe-archive")
        composeRule.onNodeWithText("Maya").performTouchInput { up() }

        // Released past the threshold: archived, gone from the list, Undo offered.
        composeRule.waitUntil(5_000) { hasText("1 conversation archived") }
        assertEquals(listOf(listOf("!maya:example.org") to true), calls.archived)
        composeRule.waitUntil(5_000) { !hasText("Maya") }
        composeRule.auditShot("archive-snackbar")
        composeRule.onNodeWithText("Undo").performClick()
        composeRule.waitUntil(5_000) { hasText("Maya") }
        assertEquals(listOf("!maya:example.org") to false, calls.archived.last())

        // A short drag springs back without archiving; swiping left archives too.
        composeRule.onNodeWithText("Design studio").performTouchInput {
            down(center)
            repeat(3) { moveBy(Offset(width * 0.03f, 0f), delayMillis = 100) }
            up()
        }
        composeRule.waitForIdle()
        assertEquals(2, calls.archived.size)
        composeRule.onNodeWithText("Design studio").performTouchInput {
            swipe(start = center, end = Offset(centerX - width * 0.6f, centerY), durationMillis = 300)
        }
        composeRule.waitUntil(5_000) { !hasText("Design studio") }
        assertEquals(listOf("!studio:example.org") to true, calls.archived.last())
    }

    @Test
    fun selectionBarPinsMutesMarksDeletesAndBlocks() {
        val calls = Calls(fixtureRooms)
        showHome(calls)

        // Long-press starts selection; taps add rows; the bar counts them.
        longPress("Maya")
        composeRule.onNodeWithContentDescription("Selected").assertIsDisplayed()
        composeRule.onNodeWithText("1").assertIsDisplayed()
        composeRule.onNodeWithText("Design studio").performClick()
        composeRule.onNodeWithText("2").assertIsDisplayed()
        listOf("Close", "Pin", "Archive", "Delete", "More options").forEach {
            composeRule.onNodeWithContentDescription(it).assertIsDisplayed()
        }
        composeRule.auditShot("conversation-selection")
        composeRule.onNodeWithContentDescription("Pin").performClick()
        composeRule.runOnIdle { assertEquals(listOf("!maya:example.org", "!studio:example.org") to true, calls.pinned.single()) }
        composeRule.onNodeWithContentDescription("Search").assertIsDisplayed()

        // Back leaves selection without acting.
        longPress("Maya")
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Search").assertIsDisplayed()

        // All selected pinned: Unpin. Groups have no Block; Mute and Mark as unread are under ⋮.
        longPress("Family")
        composeRule.onNodeWithContentDescription("Unpin").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("More options").performClick()
        composeRule.onAllNodesWithText("Block").assertCountEquals(0)
        composeRule.auditShot("conversation-selection-menu")
        composeRule.onNodeWithText("Mark as unread").performClick()
        composeRule.runOnIdle { assertEquals(listOf("!family:example.org") to true, calls.unread.single()) }
        composeRule.onAllNodesWithContentDescription("Marked unread").assertCountEquals(2)

        longPress("Design studio")
        composeRule.onNodeWithContentDescription("More options").performClick()
        composeRule.onNodeWithText("Mute").performClick()
        composeRule.runOnIdle { assertEquals(listOf("!studio:example.org") to true, calls.mutedCalls.single()) }
        composeRule.onNodeWithContentDescription("Muted").assertIsDisplayed()

        // Unread DM: Mark as read, and Block with a confirmation that explains it.
        longPress("Sam")
        composeRule.onNodeWithContentDescription("More options").performClick()
        composeRule.onNodeWithText("Mark as read").assertIsDisplayed()
        composeRule.onNodeWithText("Block").performClick()
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText("Block").fetchSemanticsNodes().isEmpty() && hasText("Yes") }
        composeRule.onNodeWithText("Block Sam?").assertIsDisplayed()
        composeRule.onNodeWithText("Unblock them any time from Spam & blocked", substring = true).assertIsDisplayed()
        composeRule.auditShot("block-confirm")
        composeRule.onNodeWithText("Yes").performClick()
        composeRule.runOnIdle { assertEquals(listOf("@sam:example.org"), calls.blockedCalls) }
        composeRule.waitUntil(5_000) { !hasText("Sam") }

        // Delete asks first, then leaves and forgets.
        longPress("Design studio")
        composeRule.onNodeWithContentDescription("Delete").performClick()
        composeRule.onNodeWithText("Delete 1 conversation?").assertIsDisplayed()
        composeRule.auditShot("delete-conversation-confirm")
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.runOnIdle { assertTrue(calls.deleted.isEmpty()) }
        composeRule.onNodeWithContentDescription("Delete").performClick()
        composeRule.onNodeWithText("Delete").performClick()
        composeRule.runOnIdle { assertEquals(listOf(listOf("!studio:example.org")), calls.deleted) }
        composeRule.waitUntil(5_000) { !hasText("Design studio") }
    }

    @Test
    fun archivedScreenUnarchivesBySwipeAndSelection() {
        val calls = Calls(fixtureRooms.map { if (it.id == "!studio:example.org") it.copy(archived = true) else it })
        var opened: MatrixRoom? = null
        composeRule.setContent {
            SendTheme {
                ArchivedScreen(
                    rooms = archivedConversations(calls.rooms.value, calls.blocked.value),
                    mutedRoomIds = calls.muted.value,
                    actions = calls.actions,
                    onOpen = { opened = it },
                    onBack = {},
                )
            }
        }
        composeRule.onNodeWithText("Archived").assertIsDisplayed()
        composeRule.onNodeWithText("Book club").assertIsDisplayed()
        composeRule.onNodeWithText("Design studio").assertIsDisplayed()
        composeRule.onAllNodesWithText("Maya").assertCountEquals(0)
        composeRule.auditShot("archived")

        composeRule.onNodeWithText("Book club").performClick()
        composeRule.runOnIdle { assertEquals("!club:example.org", opened?.id) }

        // Swipe unarchives (the track shows Unarchive), with Undo.
        val width = context.resources.displayMetrics.widthPixels.toFloat()
        composeRule.onNodeWithText("Book club").performTouchInput {
            swipe(start = center, end = Offset(centerX + width * 0.6f, centerY), durationMillis = 300)
        }
        composeRule.waitUntil(5_000) { hasText("1 conversation unarchived") }
        assertEquals(listOf("!club:example.org") to false, calls.archived.single())

        // Selection offers Unarchive and no Pin.
        longPress("Design studio")
        composeRule.onAllNodesWithContentDescription("Pin").assertCountEquals(0)
        composeRule.onNodeWithContentDescription("Unarchive").performClick()
        composeRule.runOnIdle { assertEquals(listOf("!studio:example.org") to false, calls.archived.last()) }
        composeRule.waitUntil(5_000) { hasText("Archived conversations appear here", substring = true) }
    }

    private fun hasText(text: String, substring: Boolean) =
        composeRule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun accountMenuLeadsToArchivedAndBlockedContacts() {
        var archived = false
        var blocked = false
        val unblocked = mutableListOf<String>()
        val screen = mutableStateOf("account")
        composeRule.setContent {
            SendTheme {
                when (screen.value) {
                    "account" -> AccountSheet(
                        userId = "@me:example.org",
                        securitySummary = "Device verified · recovery on",
                        onSecurity = {},
                        onSettings = {},
                        onSignOut = {},
                        onDismiss = {},
                        onArchived = { archived = true },
                        onBlocked = { blocked = true; screen.value = "blocked" },
                    )
                    else -> BlockedContactsScreen(
                        userIds = listOf("@sam:example.org", "@spam:example.org"),
                        onUnblock = { unblocked += it },
                        onBack = {},
                    )
                }
            }
        }
        composeRule.onNodeWithText("Archived").performClick()
        composeRule.runOnIdle { assertTrue(archived) }
        composeRule.onNodeWithText("Spam & blocked").performClick()
        composeRule.runOnIdle { assertTrue(blocked) }
        composeRule.onNodeWithText("@spam:example.org").assertIsDisplayed()
        composeRule.auditShot("blocked-contacts")
        composeRule.onAllNodesWithText("Unblock").onLast().performClick()
        composeRule.runOnIdle { assertEquals(listOf("@spam:example.org"), unblocked) }
    }

    private val chat = listOf(
        message("\$a1", "Are we still on for dinner tonight?", own = false, minutesAgo = 9),
        message("\$m1", "Yes! Dinner at 7 works for me", own = true, minutesAgo = 8),
        message("\$a2", "Great, I'll book the table", own = false, minutesAgo = 7),
        message("\$m2", "See you there", own = true, minutesAgo = 6),
    )

    private fun message(id: String, text: String, own: Boolean, minutesAgo: Int) = MatrixMessage(
        id = id,
        sender = if (own) "@me:example.org" else "@alice:example.org",
        text = text,
        own = own,
        timestampMillis = now - minutesAgo * minute,
        reactions = emptyList(),
        senderName = if (own) null else "Alice",
        editable = own,
    )

    @Test
    fun starredMessagesAndMultiSelect() {
        val starred = mutableStateOf(setOf("\$a1"))
        val deleted = mutableListOf<String>()
        var forwarded: List<String>? = null
        composeRule.setContent {
            SendTheme {
                ConversationScreen(
                    title = "Alice",
                    isDirect = true,
                    draft = "",
                    onDraft = {},
                    messages = chat,
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
                    onDelete = { deleted += it.id },
                    onForward = { picked -> forwarded = picked.map { it.id } },
                    starredIds = starred.value,
                    onToggleStar = { id, star -> starred.value = if (star) starred.value + id else starred.value - id },
                    loadStarred = {
                        chat.filter { it.id in starred.value }.reversed()
                            .map { StarredMessage(it.id, it.senderName ?: it.sender, it.own, it.text, it.timestampMillis) }
                    },
                )
            }
        }
        // A starred message carries a star under its bubble.
        composeRule.onNodeWithContentDescription("Starred").assertIsDisplayed()

        // Long-press → Star; the menu then offers Unstar and Select more.
        longPress("Great, I'll book the table")
        composeRule.onNodeWithText("Select more").assertIsDisplayed()
        composeRule.onNodeWithText("Star").performClick()
        composeRule.runOnIdle { assertEquals(setOf("\$a1", "\$a2"), starred.value) }
        longPress("Great, I'll book the table")
        composeRule.onNodeWithText("Unstar").assertIsDisplayed()
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        composeRule.waitForIdle()

        // ⋮ → Starred lists them; tapping one jumps back to it in the thread.
        composeRule.onNodeWithContentDescription("More options").performClick()
        composeRule.onNodeWithText("Starred").performClick()
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithContentDescription("Unstar").fetchSemanticsNodes().size == 2 }
        composeRule.auditShot("starred-messages")
        composeRule.onAllNodesWithContentDescription("Unstar")[0].performClick()
        composeRule.runOnIdle { assertEquals(setOf("\$a1"), starred.value) }
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithContentDescription("Unstar").fetchSemanticsNodes().size == 1 }
        composeRule.onAllNodesWithText("Are we still on for dinner tonight?").onLast().performClick()
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithContentDescription("Unstar").fetchSemanticsNodes().isEmpty() }

        // Select more: taps add messages; own-only selections can be deleted together (after confirming).
        longPress("Yes! Dinner at 7 works for me")
        composeRule.onNodeWithText("Select more").performClick()
        composeRule.onNodeWithText("1").assertIsDisplayed()
        composeRule.onNodeWithText("See you there").performClick()
        composeRule.onNodeWithText("2").assertIsDisplayed()
        listOf("Close", "Copy", "Forward", "Delete").forEach { composeRule.onNodeWithContentDescription(it).assertIsDisplayed() }
        composeRule.auditShot("message-selection")
        composeRule.onNodeWithContentDescription("Delete").performClick()
        composeRule.onNodeWithText("Delete 2 messages?").assertIsDisplayed()
        composeRule.onNodeWithText("Delete").performClick()
        composeRule.runOnIdle { assertEquals(listOf("\$m1", "\$m2"), deleted) }

        // Mixed selection: no Delete; Copy joins the texts in thread order; Forward hands all of them over.
        longPress("Great, I'll book the table")
        composeRule.onNodeWithText("Select more").performClick()
        composeRule.onNodeWithText("Are we still on for dinner tonight?").performClick()
        composeRule.onNodeWithText("Yes! Dinner at 7 works for me").performClick()
        composeRule.onNodeWithText("3").assertIsDisplayed()
        composeRule.onAllNodesWithContentDescription("Delete").assertCountEquals(0)
        composeRule.onNodeWithContentDescription("Copy").performClick()
        composeRule.runOnIdle {
            val clip = context.getSystemService(ClipboardManager::class.java).primaryClip
            assertEquals(
                "Are we still on for dinner tonight?\nYes! Dinner at 7 works for me\nGreat, I'll book the table",
                clip?.getItemAt(0)?.text?.toString(),
            )
        }
        longPress("See you there")
        composeRule.onNodeWithText("Select more").performClick()
        composeRule.onNodeWithText("Great, I'll book the table").performClick()
        composeRule.onNodeWithContentDescription("Forward").performClick()
        composeRule.runOnIdle { assertEquals(listOf("\$a2", "\$m2"), forwarded) }
    }
}
