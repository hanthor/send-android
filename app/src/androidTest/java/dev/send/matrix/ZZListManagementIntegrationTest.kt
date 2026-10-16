package dev.send.matrix

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.swipe
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.send.matrix.notifications.NotificationRouter
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.ClientBuilder
import org.matrix.rustcomponents.sdk.MessageType
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.RoomInfo
import org.matrix.rustcomponents.sdk.SlidingSyncVersionBuilder
import org.matrix.rustcomponents.sdk.SqliteStoreBuilder
import org.matrix.rustcomponents.sdk.SyncService
import org.matrix.rustcomponents.sdk.TextMessageContent
import org.matrix.rustcomponents.sdk.Timeline

/**
 * Phase 5 acceptance against the local Synapse: Alice manages her conversation list through the app's gestures and
 * every change is checked where the homeserver echoes it back — room tags (archive = `m.lowpriority`, pin =
 * `m.favourite`), the `m.marked_unread` flag, leave + forget, `m.ignored_user_list` and the `dev.send.starred`
 * account data, which must also survive a cold restore of the session. Bob, an independent SDK client, sends the
 * messages and sees Alice leave.
 */
@RunWith(AndroidJUnit4::class)
class ZZListManagementIntegrationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val repository get() = MatrixRepository.get(context)

    private var bobClient: Client? = null
    private var bobSync: SyncService? = null
    private val bobRooms = ConcurrentHashMap<String, Room>()
    private val bobTimelines = ConcurrentHashMap<String, Timeline>()
    /** Access token of a REST-only Alice device that reads her account data straight from Synapse. */
    private var probeToken: String? = null
    /** Checks that failed without stopping the later steps; the test fails on them at the end. */
    private val softFailures = mutableListOf<String>()

    @After
    fun cleanUp() {
        // Later suites message Bob: never leave him blocked, even when an assertion failed mid-way.
        runCatching { runBlocking { if (BOB in repository.refreshIgnoredUsers()) repository.unblockUser(BOB) } }
        probeToken?.let { token -> runCatching { http("POST", "/_matrix/client/v3/logout", token, "{}") } }
        bobTimelines.values.forEach { it.close() }
        bobRooms.values.forEach { it.close() }
        bobSync?.close()
        bobClient?.close()
    }

    @Test
    fun archivePinUnreadDeleteBlockAndStarRoundTripThroughSynapse() {
        composeRule.signInAsAliceIfNeeded()
        waitFor("Alice's session", 60_000, { "account=${repository.account.value}" }) { repository.account.value != null }
        val stamp = System.currentTimeMillis()
        val archiveName = "Archive me $stamp"
        val pinName = "Pin me $stamp"
        val deleteName = "Delete me $stamp"
        val archiveRoom = runBlocking { repository.createEncryptedGroup(archiveName, listOf(BOB)) }
        val pinRoom = runBlocking { repository.createEncryptedGroup(pinName, listOf(BOB)) }
        val deleteRoom = runBlocking { repository.createEncryptedGroup(deleteName, listOf(BOB)) }
        val dm = runBlocking { repository.createEncryptedDirectRoom(BOB) }
        startBob(archiveRoom, pinRoom, deleteRoom, dm)
        waitForUi("the new conversations in Alice's list", 60_000, ::roomsState) {
            listOf(archiveName, pinName, deleteName).all { rowListed(it) } &&
                listOf(archiveRoom, pinRoom, deleteRoom, dm).all { id -> repository.joinedRooms.value.any { it.id == id && it.encrypted } }
        }

        // 1. Swipe archives: Messages' snackbar, the m.lowpriority tag comes back through sync, the row leaves the list.
        swipeRow(archiveName, towardsEnd = true)
        waitForUi("the archive snackbar", 10_000, ::roomsState) { hasNodeWithText("1 conversation archived") }
        waitFor("m.lowpriority on the homeserver", 60_000, { "serverTags=${serverTags(archiveRoom)}" }) { "m.lowpriority" in serverTags(archiveRoom) }
        waitFor("m.lowpriority on the archived room", 60_000, ::roomsState) { roomInfo(archiveRoom)?.isLowPriority == true }
        waitForUi("the archived row to leave the list", 30_000, ::roomsState) { !hasNodeWithText(archiveName) }

        // A new message from Bob keeps it archived (as in Messages); it is still delivered and summarised.
        val stillArchived = "Still archived $stamp"
        bobSends(archiveRoom, stillArchived)
        waitFor("Bob's message in the archived room's summary", 60_000, ::roomsState) {
            repository.joinedRooms.value.firstOrNull { it.id == archiveRoom }?.lastMessage == stillArchived
        }
        assertTrue(roomInfo(archiveRoom)?.isLowPriority == true)
        assertTrue(repository.joinedRooms.value.first { it.id == archiveRoom }.archived)
        assertFalse(hasNodeWithText(archiveName))

        // Account menu → Archived lists it; swiping it there unarchives (tag removed on the homeserver).
        openAccountItem("Archived")
        waitForUi("the archived conversation on the Archived screen", 30_000, ::roomsState) { rowListed(archiveName) }
        composeRule.auditShot("live-archived")
        swipeRow(archiveName, towardsEnd = false)
        // The action fires once the row's spring settles off-screen; the test clock only runs frames while Compose
        // is waited on, so wait on the UI (Messages' snackbar) before polling the homeserver.
        waitForUi("the unarchive snackbar", 10_000, ::roomsState) { hasNodeWithText("1 conversation unarchived") }
        softly {
            waitFor("m.lowpriority removed on the homeserver", 30_000, { "serverTags=${serverTags(archiveRoom)}" }) { "m.lowpriority" !in serverTags(archiveRoom) }
        }
        softly {
            waitFor("m.lowpriority removed in Alice's synced room info", 60_000, { "serverTags=${serverTags(archiveRoom)} local=${roomInfo(archiveRoom)?.isLowPriority} ${roomsState()}" }) {
                roomInfo(archiveRoom)?.isLowPriority == false
            }
        }
        composeRule.onNodeWithContentDescription("Back").performClick()
        softly { waitForUi("the unarchived row back in the list", 30_000, ::roomsState) { rowListed(archiveName) } }

        // 2. Pin from the selection bar: m.favourite on the homeserver, and the row sorts above newer ones.
        longPressRow(pinName)
        composeRule.onNodeWithContentDescription("Pin").performClick()
        waitFor("m.favourite on the homeserver", 60_000, { "serverTags=${serverTags(pinRoom)}" }) { "m.favourite" in serverTags(pinRoom) }
        waitFor("m.favourite on the pinned room", 60_000, ::roomsState) { roomInfo(pinRoom)?.isFavourite == true }
        waitForUi("the pinned row first", 30_000, ::roomsState) {
            rowListed(pinName) && hasNodeWithText(deleteName) && top(pinName) < top(deleteName)
        }
        assertEquals(pinRoom, inboxConversations(repository.joinedRooms.value, repository.ignoredUsers.value).first().id)
        assertTrue(composeRule.onAllNodesWithContentDescription("Pinned").fetchSemanticsNodes().isNotEmpty())

        // 3. Mark as unread sets m.marked_unread and the row's unread dot; Mark as read clears it again.
        longPressRow(deleteName)
        composeRule.onNodeWithContentDescription("More options").performClick()
        composeRule.onNodeWithText("Mark as unread").performClick()
        waitFor("m.marked_unread on the homeserver", 60_000, { "server=${serverRoomData(deleteRoom, "m.marked_unread")}" }) {
            serverRoomData(deleteRoom, "m.marked_unread")?.optBoolean("unread") == true
        }
        waitFor("m.marked_unread on the room", 60_000, ::roomsState) { roomInfo(deleteRoom)?.isMarkedUnread == true }
        waitForUi("the unread dot", 30_000, ::roomsState) {
            composeRule.onAllNodesWithContentDescription("Marked unread").fetchSemanticsNodes().isNotEmpty() &&
                repository.joinedRooms.value.first { it.id == deleteRoom }.markedUnread
        }
        composeRule.auditShot("live-marked-unread")
        longPressRow(deleteName)
        composeRule.onNodeWithContentDescription("More options").performClick()
        composeRule.onNodeWithText("Mark as read").performClick()
        softly {
            waitFor("m.marked_unread cleared on the homeserver", 30_000, { "server=${serverRoomData(deleteRoom, "m.marked_unread")}" }) {
                serverRoomData(deleteRoom, "m.marked_unread")?.optBoolean("unread") == false
            }
        }
        softly {
            waitFor("m.marked_unread cleared in Alice's synced room info", 60_000, ::roomsState) { roomInfo(deleteRoom)?.isMarkedUnread == false }
        }

        // 4. Delete after confirming: Alice leaves (Bob sees one member left) and forgets the room.
        longPressRow(deleteName)
        composeRule.onNodeWithContentDescription("Delete").performClick()
        waitForUi("the delete confirmation", 10_000, ::roomsState) { hasNodeWithText("Delete 1 conversation?") }
        composeRule.onNodeWithText("Delete").performClick()
        waitFor("Alice to leave the room on the homeserver", 60_000, { "joined=${serverJoinedRooms()}" }) { deleteRoom !in serverJoinedRooms() }
        // Forget leaves no joined membership behind on Alice's client (the SDK may keep the left room until sync drops it).
        waitFor("Alice's client to see the room left or forgotten", 60_000, ::roomsState) { !aliceJoined(deleteRoom) }
        waitFor("Bob to see Alice leave", 60_000, ::roomsState) {
            runBlocking(Dispatchers.IO) { checkNotNull(bobRooms[deleteRoom]).roomInfo().joinedMembersCount } == 1uL
        }
        waitForUi("the deleted row gone", 30_000, ::roomsState) { !hasNodeWithText(deleteName) }

        // 5. Star Bob's message: dev.send.starred account data round-trips through sync, the bubble shows the star,
        // and ⋮ → Starred lists it and jumps back to it.
        val starMe = "Star me $stamp"
        bobSends(pinRoom, starMe)
        composeRule.runOnUiThread { composeRule.activity.pendingConversation.value = pinRoom }
        waitForUi("Bob's message in the pinned conversation", 60_000, ::conversationState) {
            NotificationRouter.visibleRoomId == pinRoom && hasNodeWithText(starMe)
        }
        val starredId = repository.messages.value.first { it.text == starMe }.id
        composeRule.onNodeWithText(starMe).performTouchInput { longClick() }
        waitForUi("the Star action", 10_000, ::conversationState) { hasNodeWithText("Star") }
        composeRule.onNodeWithText("Star").performClick()
        waitFor("the star in dev.send.starred on the homeserver", 60_000, { "server=${serverAccountData(MatrixRepository.STARRED_EVENT_TYPE)}" }) {
            MatrixRepository.parseStarred(serverAccountData(MatrixRepository.STARRED_EVENT_TYPE)?.toString())[pinRoom]?.contains(starredId) == true
        }
        waitFor("the star back through sync into the store", 60_000, { "starred=${repository.starredEvents.value}" }) {
            runBlocking { repository.refreshStarred() }[pinRoom]?.contains(starredId) == true
        }
        waitForUi("the star under the bubble", 30_000, ::conversationState) {
            composeRule.onAllNodesWithContentDescription("Starred").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription("More options").performClick()
        composeRule.onNodeWithText("Starred").performClick()
        waitForUi("the starred list", 30_000, ::conversationState) {
            composeRule.onAllNodesWithContentDescription("Unstar").fetchSemanticsNodes().size == 1 &&
                composeRule.onAllNodesWithText(starMe).fetchSemanticsNodes().size == 2
        }
        composeRule.auditShot("live-starred")
        composeRule.onAllNodesWithText(starMe).onLast().performClick()
        waitForUi("the starred list to close", 10_000, ::conversationState) {
            composeRule.onAllNodesWithContentDescription("Unstar").fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithContentDescription("Back").performClick()
        waitForUi("the conversation list", 30_000, ::roomsState) {
            composeRule.onAllNodesWithContentDescription("Search").fetchSemanticsNodes().isNotEmpty()
        }

        // 6. Block Bob from his DM: he joins m.ignored_user_list, his DMs leave the list; Blocked contacts unblocks.
        val blockCheck = "Block check $stamp"
        bobSends(dm, blockCheck)
        waitForUi("Bob's DM in the list", 60_000, ::roomsState) {
            rowListed(blockCheck) && repository.joinedRooms.value.firstOrNull { it.id == dm }?.dmUserId == BOB
        }
        longPressRow(blockCheck)
        composeRule.onNodeWithContentDescription("More options").performClick()
        composeRule.onNodeWithText("Block").performClick()
        waitForUi("the block confirmation", 10_000, ::roomsState) {
            hasNodeWithText("Yes") && hasNodeWithText("Unblock them any time", substring = true)
        }
        composeRule.onNodeWithText("Yes").performClick()
        waitFor("Bob in m.ignored_user_list on the homeserver", 60_000, { "server=${serverAccountData("m.ignored_user_list")}" }) {
            serverAccountData("m.ignored_user_list")?.optJSONObject("ignored_users")?.has(BOB) == true
        }
        waitFor("Bob in Alice's synced ignored users", 60_000, { "ignored=${repository.ignoredUsers.value}" }) {
            BOB in runBlocking { repository.refreshIgnoredUsers() }
        }
        waitForUi("Bob's DM gone from the list", 30_000, ::roomsState) {
            !hasNodeWithText(blockCheck) && inboxConversations(repository.joinedRooms.value, repository.ignoredUsers.value).none { it.id == dm }
        }
        openAccountItem("Blocked contacts")
        waitForUi("Bob under Blocked contacts", 30_000, ::roomsState) { hasNodeWithText(BOB) }
        composeRule.auditShot("live-blocked")
        composeRule.onNodeWithText("Unblock").performClick()
        waitFor("Bob removed from m.ignored_user_list on the homeserver", 60_000, { "server=${serverAccountData("m.ignored_user_list")}" }) {
            serverAccountData("m.ignored_user_list")?.optJSONObject("ignored_users")?.has(BOB) == false
        }
        softly {
            waitFor("Bob unblocked in Alice's synced ignored users", 60_000, { "ignored=${repository.ignoredUsers.value}" }) {
                BOB !in runBlocking { repository.refreshIgnoredUsers() }
            }
        }
        composeRule.onNodeWithContentDescription("Back").performClick()
        softly {
            waitFor("Bob's DM back in the list", 60_000, ::roomsState) {
                inboxConversations(repository.joinedRooms.value, repository.ignoredUsers.value).any { it.id == dm }
            }
        }

        // 7. Cold restore: the starred message comes back from the store the synced account data was saved in.
        runBlocking { repository.close() }
        assertTrue(repository.starredEvents.value.isEmpty())
        composeRule.activityRule.scenario.recreate()
        composeRule.waitUntil(timeoutMillis = 60_000) {
            composeRule.onAllNodesWithContentDescription("Search").fetchSemanticsNodes().isNotEmpty() && repository.account.value != null
        }
        waitFor("the star after restore", 60_000, { "starred=${repository.starredEvents.value}" }) {
            repository.starredEvents.value[pinRoom]?.contains(starredId) == true
        }
        waitFor("the pin after restore", 60_000, ::roomsState) {
            repository.joinedRooms.value.firstOrNull { it.id == pinRoom }?.pinned == true
        }
        assertTrue("Checks that failed along the way:\n${softFailures.joinToString("\n")}", softFailures.isEmpty())
    }

    /** Runs [check]; a failure is recorded for the end of the test instead of stopping the later steps. */
    private fun softly(check: () -> Unit) {
        try {
            check()
        } catch (failure: AssertionError) {
            softFailures += failure.message.orEmpty()
            android.util.Log.w("ListManagementTest", "Soft failure: ${failure.message}")
        }
    }

    /** Alice's REST probe device (logged out after the test). */
    private fun probe(): String = probeToken ?: run {
        val body = org.json.JSONObject()
            .put("type", "m.login.password")
            .put("identifier", org.json.JSONObject().put("type", "m.id.user").put("user", ALICE))
            .put("password", DEMO_PASSWORD)
            .put("initial_device_display_name", "Send list test (REST)")
        org.json.JSONObject(http("POST", "/_matrix/client/v3/login", null, body.toString())).getString("access_token").also { probeToken = it }
    }

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    /** The room's tags as Synapse has them. */
    private fun serverTags(roomId: String): Set<String> {
        val json = org.json.JSONObject(http("GET", "/_matrix/client/v3/user/${encode(ALICE)}/rooms/${encode(roomId)}/tags", probe(), null))
        return json.optJSONObject("tags")?.keys()?.asSequence()?.toSet().orEmpty()
    }

    /** Alice's room account data of [type] on Synapse, or null when unset. */
    private fun serverRoomData(roomId: String, type: String): org.json.JSONObject? = runCatching {
        org.json.JSONObject(http("GET", "/_matrix/client/v3/user/${encode(ALICE)}/rooms/${encode(roomId)}/account_data/${encode(type)}", probe(), null))
    }.getOrNull()

    /** Alice's global account data of [type] on Synapse, or null when unset. */
    private fun serverAccountData(type: String): org.json.JSONObject? = runCatching {
        org.json.JSONObject(http("GET", "/_matrix/client/v3/user/${encode(ALICE)}/account_data/${encode(type)}", probe(), null))
    }.getOrNull()

    private fun http(method: String, path: String, token: String?, body: String?): String {
        val connection = java.net.URL("$SYNAPSE_URL$path").openConnection() as java.net.HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = connection.responseCode
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            check(code in 200..299) { "$method $path → $code $text" }
            return text
        } finally {
            connection.disconnect()
        }
    }

    private fun hasNodeWithText(text: String, substring: Boolean = false): Boolean =
        composeRule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()

    /**
     * Whether a row showing [text] is in the list on screen, scrolling the list to it first: rows below the fold
     * aren't composed. A room that has no recency stamp from sync yet sorts after every room that has one (then by
     * name), so a just-created conversation can sit far down a long list until its first message.
     */
    private fun rowListed(text: String): Boolean {
        if (hasNodeWithText(text)) return true
        val scrolled = runCatching {
            composeRule.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText(text))
        }.isSuccess
        return scrolled && hasNodeWithText(text)
    }

    private fun top(text: String): Float = composeRule.onAllNodesWithText(text).fetchSemanticsNodes().first().boundsInRoot.top

    /** Swipes the conversation row showing [text] most of the screen width, towards the end or the start. */
    private fun swipeRow(text: String, towardsEnd: Boolean) {
        val width = context.resources.displayMetrics.widthPixels.toFloat()
        check(rowListed(text)) { "No row \"$text\"; ${roomsState()}" }
        composeRule.onNodeWithText(text).performTouchInput {
            swipe(start = center, end = Offset(centerX + if (towardsEnd) width * 0.6f else -width * 0.6f, centerY), durationMillis = 300)
        }
    }

    /** Long-presses the row showing [text] and waits for the selection bar. */
    private fun longPressRow(text: String) {
        check(rowListed(text)) { "No row \"$text\"; ${roomsState()}" }
        composeRule.onNodeWithText(text).performTouchInput { longClick() }
        waitForUi("the selection bar for \"$text\"", 10_000, ::roomsState) {
            composeRule.onAllNodesWithContentDescription("Close").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun openAccountItem(label: String) {
        composeRule.onNodeWithContentDescription("Account").performClick()
        waitForUi("the account menu", 10_000, ::roomsState) { hasNodeWithText(label) }
        composeRule.onNodeWithText(label).performClick()
    }

    /** Alice's room info as her client stores it (tags and flags arrive there through sync). */
    private fun roomInfo(roomId: String): RoomInfo? =
        repository.sdkRoom(roomId)?.use { room -> runBlocking(Dispatchers.IO) { room.roomInfo() } }

    private fun aliceJoined(roomId: String): Boolean =
        repository.sdkRoom(roomId)?.use { it.membership() == org.matrix.rustcomponents.sdk.Membership.JOINED } == true

    /** Rooms Alice is joined to, as Synapse has them. */
    private fun serverJoinedRooms(): Set<String> {
        val rooms = org.json.JSONObject(http("GET", "/_matrix/client/v3/joined_rooms", probe(), null)).getJSONArray("joined_rooms")
        return List(rooms.length()) { rooms.getString(it) }.toSet()
    }

    private fun roomsState(): String =
        "rooms=${repository.joinedRooms.value.map { "${it.name}:encrypted=${it.encrypted}:pinned=${it.pinned}:archived=${it.archived}:unread=${it.markedUnread}:dm=${it.dmUserId}:${it.lastMessage}" }} " +
            "ignored=${repository.ignoredUsers.value} onScreen=${screenTexts()}"

    /** Every text on screen, for timeout reports. */
    private fun screenTexts(): List<String> = runCatching {
        composeRule.onAllNodes(androidx.compose.ui.test.SemanticsMatcher("any node") { true }).fetchSemanticsNodes().mapNotNull { node ->
            node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.joinToString(" | ")
        }
    }.getOrElse { listOf("unavailable: ${it.message}") }

    private fun conversationState(): String =
        "visible=${NotificationRouter.visibleRoomId} messages=${repository.messages.value.map { "${it.sender}:${it.text}" }}"

    /** Compose's waitUntil (keeps the app's UI composing), reporting app state on timeout. */
    private fun waitForUi(what: String, timeoutMillis: Long, state: () -> String, condition: () -> Boolean) {
        try {
            composeRule.waitUntil(timeoutMillis = timeoutMillis, condition = condition)
        } catch (timeout: ComposeTimeoutException) {
            throw AssertionError("Timed out waiting for $what; ${state()}; earlier soft failures=$softFailures", timeout)
        }
    }

    private fun waitFor(what: String, timeoutMillis: Long, state: () -> String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("Timed out waiting for $what; ${state()}; earlier soft failures=$softFailures")
            Thread.sleep(250)
        }
    }

    private fun startBob(vararg roomIds: String) {
        val root = context.filesDir.resolve("matrix-list-bob")
        root.deleteRecursively()
        root.mkdirs()
        val client = runBlocking(Dispatchers.IO) {
            ClientBuilder()
                .homeserverUrl(SYNAPSE_URL)
                .slidingSyncVersionBuilder(SlidingSyncVersionBuilder.NATIVE)
                .sqliteStore(SqliteStoreBuilder(root.resolve("store.db").absolutePath, "").key(ByteArray(32)))
                .sessionPaths(root.resolve("cache").absolutePath, root.resolve("crypto").absolutePath)
                .build()
        }
        bobClient = client
        runBlocking(Dispatchers.IO) {
            client.login(BOB, DEMO_PASSWORD, "Send list management test", null)
            val sync = client.syncService().finish()
            bobSync = sync
            sync.start()
            for (roomId in roomIds) {
                val room = client.joinWhenSynced(roomId)
                bobRooms[roomId] = room
                bobTimelines[roomId] = room.timeline()
            }
        }
    }

    /** Bob sends [body] to [roomId]. */
    private fun bobSends(roomId: String, body: String) {
        val timeline = checkNotNull(bobTimelines[roomId])
        runBlocking(Dispatchers.IO) {
            timeline.send(checkNotNull(timeline.createMessageContent(MessageType.Text(TextMessageContent(body, null)))))
        }
    }
}
