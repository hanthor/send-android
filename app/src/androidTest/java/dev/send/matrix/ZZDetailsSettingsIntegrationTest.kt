package dev.send.matrix

import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.send.matrix.notifications.NotificationRouter
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.ClientBuilder
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.SlidingSyncVersionBuilder
import org.matrix.rustcomponents.sdk.SqliteStoreBuilder
import org.matrix.rustcomponents.sdk.SyncService

/**
 * Phase 6 acceptance against the local Synapse: through conversation details Alice renames a group and sets its
 * topic (Bob's client sees both), invites Carol (an invite membership on the server) and changes the room's
 * notification level (the push rules on the server follow); on her profile page she changes her display name (Bob
 * sees it); under Settings → Devices she signs a REST probe session out with her password (the device is gone from
 * Synapse and its token stops working).
 */
@RunWith(AndroidJUnit4::class)
class ZZDetailsSettingsIntegrationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val repository get() = MatrixRepository.get(context)

    private var bobClient: Client? = null
    private var bobSync: SyncService? = null
    private val bobRooms = ConcurrentHashMap<String, Room>()
    /** A REST-only Alice device that reads her server-side state (logged out after the test). */
    private var readerToken: String? = null
    private val softFailures = mutableListOf<String>()

    @After
    fun cleanUp() {
        // Later suites expect Alice under her default name.
        runCatching { runBlocking { repository.setDisplayName("alice") } }
        readerToken?.let { token -> runCatching { http("POST", "/_matrix/client/v3/logout", token, "{}") } }
        bobRooms.values.forEach { it.close() }
        bobSync?.close()
        bobClient?.close()
    }

    @Test
    fun detailsProfileAndDevicesRoundTripThroughSynapse() {
        composeRule.signInAsAliceIfNeeded()
        waitFor("Alice's session", 60_000, { "account=${repository.account.value}" }) { repository.account.value != null }
        val stamp = System.currentTimeMillis()
        val groupName = "Details $stamp"
        val room = runBlocking { repository.createEncryptedGroup(groupName, listOf(BOB)) }
        startBob(room)
        waitFor("the group in Alice's list with Bob joined", 60_000, ::state) {
            repository.joinedRooms.value.any { it.id == room && it.encrypted } && serverMembership(room, BOB) == "join"
        }

        // Open the group, then ⋮ → Details.
        composeRule.runOnUiThread { composeRule.activity.pendingConversation.value = room }
        waitForUi("the group on screen", 60_000, ::state) {
            NotificationRouter.visibleRoomId == room && composeRule.onAllNodesWithContentDescription("More options").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription("More options").performClick()
        composeRule.onNodeWithText("Details").performClick()
        waitForUi("details with Alice and Bob", 60_000, ::state) { hasNodeWithText("2 people") }
        composeRule.auditShot("live-details-group")

        // 1. Rename: Bob's client shows the new name.
        val newName = "Renamed $stamp"
        editDetailsText("Group name", DETAILS_TEXT_INPUT_TAG, newName, "Save")
        waitFor("m.room.name on the homeserver", 60_000, { "name=${serverState(room, "m.room.name")}" }) {
            serverState(room, "m.room.name")?.optString("name") == newName
        }
        waitFor("the new name on Bob's client", 60_000, { "bob=${bobRooms[room]?.displayName()}" }) {
            bobRooms[room]?.displayName() == newName
        }

        // 2. Topic: Bob's client shows it.
        val topic = "Topic $stamp"
        editDetailsText("Description", DETAILS_TEXT_INPUT_TAG, topic, "Save")
        waitFor("the topic on Bob's client", 60_000, { "bob=${bobRooms[room]?.topic()} server=${serverState(room, "m.room.topic")}" }) {
            bobRooms[room]?.topic() == topic
        }

        // 3. Add people: Carol's invite is on the server and listed as Invited.
        editDetailsText("Add people", ADD_PEOPLE_INPUT_TAG, CAROL, "Invite")
        waitFor("Carol's invite on the homeserver", 60_000, { "carol=${serverMembership(room, CAROL)}" }) {
            serverMembership(room, CAROL) == "invite"
        }
        softly { waitForUi("Carol listed as Invited", 60_000, ::state) { hasNodeWithText("Invited") } }

        // 4. Notifications: Mentions only and Off are push rules on the server; All messages removes both.
        setNotificationLevel("Mentions only")
        waitFor("a mentions-only room rule on the homeserver", 60_000, { "rules=${pushRules()}" }) {
            val rule = rule("room", room)
            rule != null && rule.optBoolean("enabled", true) && !notifies(rule)
        }
        softly { waitForUi("details showing Mentions only", 30_000, ::state) { hasNodeWithText("Mentions only") } }
        setNotificationLevel("Off")
        waitFor("a mute override rule on the homeserver", 60_000, { "rules=${pushRules()}" }) {
            rule("override", room)?.let { !notifies(it) } == true
        }
        waitFor("the room muted in Alice's client", 30_000, ::state) { room in repository.mutedRoomIds.value }
        setNotificationLevel("All messages")
        waitFor("the room's mute and mentions-only rules gone", 60_000, { "rules=${pushRules()}" }) {
            rule("override", room) == null && rule("room", room)?.let { notifies(it) } != false
        }
        // The level is read back from the server.
        closeDetails()
        composeRule.onNodeWithContentDescription("More options").performClick()
        composeRule.onNodeWithText("Details").performClick()
        waitForUi("details reloaded with All messages", 60_000, ::state) { hasNodeWithText("All messages") }
        closeDetails()
        // Back to the list.
        pressBack()
        waitForUi("the conversation list", 30_000, ::state) {
            composeRule.onAllNodesWithContentDescription("Account").fetchSemanticsNodes().isNotEmpty()
        }

        // 5. Profile: the new display name reaches Bob.
        val displayName = "Alice $stamp"
        composeRule.onNodeWithContentDescription("Account").performClick()
        waitForUi("the account menu", 10_000, ::state) { hasNodeWithText(ALICE) }
        composeRule.onNodeWithText(ALICE).performClick()
        waitForUi("the profile page", 10_000, ::state) { hasNodeWithText("Matrix ID") }
        composeRule.auditShot("live-profile")
        composeRule.onNodeWithTag(PROFILE_NAME_TAG).performTextReplacement(displayName)
        composeRule.onNodeWithText("Save").performClick()
        waitFor("Alice's new name on Bob's client", 60_000, { "bob=${bobProfileName()} member=${bobRooms[room]?.let { r -> runCatching { runBlocking(Dispatchers.IO) { r.memberDisplayName(ALICE) } }.getOrNull() }}" }) {
            bobProfileName() == displayName
        }
        softly {
            waitFor("Alice's new name in the shared room on Bob's client", 60_000, { "member=${bobRooms[room]?.let { r -> runCatching { runBlocking(Dispatchers.IO) { r.memberDisplayName(ALICE) } }.getOrNull() }}" }) {
                bobRooms[room]?.let { r -> runCatching { runBlocking(Dispatchers.IO) { r.memberDisplayName(ALICE) } }.getOrNull() } == displayName
            }
        }
        pressBack()
        waitForUi("the profile page to close", 10_000, ::state) { !hasNodeWithText("Matrix ID") }

        // 6. Devices: sign a probe session out with the password; Synapse drops it and its token dies.
        val probeName = "Send probe $stamp"
        val (probeDevice, probeToken) = loginProbe(probeName)
        composeRule.onNodeWithContentDescription("Account").performClick()
        waitForUi("the account menu", 10_000, ::state) { hasNodeWithText("Settings") }
        composeRule.onNodeWithText("Settings").performClick()
        waitForUi("Settings", 10_000, ::state) { hasNodeWithText("Choose theme") }
        composeRule.onNodeWithText("Devices").performScrollTo().performClick()
        waitForUi("the probe in Devices", 60_000, ::state) { hasNodeWithText(probeName) }
        composeRule.auditShot("live-devices")
        composeRule.onNode(hasText("Sign out") and hasAnyAncestor(hasTestTag("device-$probeDevice"))).performScrollTo().performClick()
        waitForUi("the sign-out confirmation", 10_000, ::state) { hasNodeWithText("Sign out of $probeName?") }
        composeRule.onAllNodesWithText("Sign out").let { it[it.fetchSemanticsNodes().size - 1] }.performClick()
        waitForUi("the password prompt", 30_000, ::state) { hasNodeWithText("Confirm it's you") }
        composeRule.onNodeWithTag(DEVICE_PASSWORD_TAG).performTextInput(DEMO_PASSWORD)
        composeRule.onAllNodesWithText("Sign out").let { it[it.fetchSemanticsNodes().size - 1] }.performClick()
        waitFor("the probe gone from Synapse", 60_000, { "devices=${runCatching { runBlocking { repository.devices() } }.getOrNull()}" }) {
            runBlocking { repository.devices() }.none { it.id == probeDevice }
        }
        waitFor("the probe's token rejected", 30_000, { "whoami=${runCatching { http("GET", "/_matrix/client/v3/account/whoami", probeToken, null) }}" }) {
            runCatching { http("GET", "/_matrix/client/v3/account/whoami", probeToken, null) }.isFailure
        }
        waitForUi("the probe gone from Devices", 30_000, ::state) { !hasNodeWithText(probeName) }
        assertTrue("Checks that failed along the way:\n${softFailures.joinToString("\n")}", softFailures.isEmpty())
    }

    /** System back, as the user's gesture: the topmost BackHandler (details, profile, conversation) handles it. */
    private fun pressBack() {
        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
    }

    private fun closeDetails() {
        pressBack()
        waitForUi("details to close", 10_000, ::state) {
            composeRule.onAllNodes(hasTestTag(DETAILS_LIST_TAG)).fetchSemanticsNodes().isEmpty()
        }
    }

    /** Opens a details row's one-field dialog, enters [value], confirms, and waits for the dialog to close (saved). */
    private fun editDetailsText(row: String, inputTag: String, value: String, confirm: String) {
        scrollDetailsTo(row)
        composeRule.onNodeWithText(row).performClick()
        waitForUi("the $row dialog", 10_000, ::state) { composeRule.onAllNodes(hasTestTag(inputTag)).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag(inputTag).performTextReplacement(value)
        composeRule.onNodeWithText(confirm).performClick()
        waitForUi("the $row dialog to close", 60_000, ::state) { composeRule.onAllNodes(hasTestTag(inputTag)).fetchSemanticsNodes().isEmpty() }
    }

    private fun scrollDetailsTo(text: String) {
        composeRule.onNodeWithTag(DETAILS_LIST_TAG).performScrollToNode(hasText(text))
    }

    private fun setNotificationLevel(label: String) {
        scrollDetailsTo("Notifications")
        composeRule.onNodeWithText("Notifications").performClick()
        waitForUi("the notification levels", 10_000, ::state) { hasNodeWithText("Only when someone mentions you") }
        // The dialog's option (its window comes after the details row showing the current level).
        composeRule.onAllNodesWithText(label).let { it[it.fetchSemanticsNodes().size - 1] }.performClick()
        waitForUi("the levels dialog to close", 10_000, ::state) { !hasNodeWithText("Only when someone mentions you") }
    }

    /** A push rule of [kind] whose id is [ruleId], as Synapse has it. */
    private fun rule(kind: String, ruleId: String): org.json.JSONObject? {
        val rules = pushRules().optJSONObject("global")?.optJSONArray(kind) ?: return null
        return (0 until rules.length()).map { rules.getJSONObject(it) }.firstOrNull { it.optString("rule_id") == ruleId }
    }

    private fun notifies(rule: org.json.JSONObject): Boolean {
        val actions = rule.optJSONArray("actions") ?: return false
        return (0 until actions.length()).any { actions.opt(it) == "notify" }
    }

    private fun pushRules(): org.json.JSONObject = org.json.JSONObject(http("GET", "/_matrix/client/v3/pushrules/", reader(), null))

    private fun serverState(roomId: String, type: String, key: String = ""): org.json.JSONObject? = runCatching {
        org.json.JSONObject(http("GET", "/_matrix/client/v3/rooms/${encode(roomId)}/state/${encode(type)}/${encode(key)}", reader(), null))
    }.getOrNull()

    private fun serverMembership(roomId: String, userId: String): String? = serverState(roomId, "m.room.member", userId)?.optString("membership")

    private fun bobProfileName(): String? = runCatching { runBlocking(Dispatchers.IO) { bobClient?.getProfile(ALICE)?.displayName } }.getOrNull()

    /** Alice's REST reader device. */
    private fun reader(): String = readerToken ?: loginProbe("Send details test (REST)").second.also { readerToken = it }

    /** Logs a REST-only Alice device in; returns its device ID and access token. */
    private fun loginProbe(name: String): Pair<String, String> {
        val body = org.json.JSONObject()
            .put("type", "m.login.password")
            .put("identifier", org.json.JSONObject().put("type", "m.id.user").put("user", ALICE))
            .put("password", DEMO_PASSWORD)
            .put("initial_device_display_name", name)
        val json = org.json.JSONObject(http("POST", "/_matrix/client/v3/login", null, body.toString()))
        return json.getString("device_id") to json.getString("access_token")
    }

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

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

    private fun softly(check: () -> Unit) {
        try {
            check()
        } catch (failure: AssertionError) {
            softFailures += failure.message.orEmpty()
            android.util.Log.w("DetailsSettingsTest", "Soft failure: ${failure.message}")
        }
    }

    private fun hasNodeWithText(text: String): Boolean = composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun state(): String =
        "visible=${NotificationRouter.visibleRoomId} muted=${repository.mutedRoomIds.value} profile=${repository.ownProfile.value} onScreen=${screenTexts()}"

    private fun screenTexts(): List<String> = runCatching {
        composeRule.onAllNodes(androidx.compose.ui.test.SemanticsMatcher("any node") { true }).fetchSemanticsNodes().mapNotNull { node ->
            node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.joinToString(" | ")
        }
    }.getOrElse { listOf("unavailable: ${it.message}") }

    private fun waitForUi(what: String, timeoutMillis: Long, state: () -> String, condition: () -> Boolean) {
        try {
            composeRule.waitUntil(timeoutMillis = timeoutMillis, condition = condition)
        } catch (timeout: ComposeTimeoutException) {
            throw AssertionError("Timed out waiting for $what; ${state()}; earlier soft failures=$softFailures", timeout)
        }
    }

    private fun waitFor(what: String, timeoutMillis: Long, state: () -> String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!runCatching(condition).getOrDefault(false)) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("Timed out waiting for $what; ${state()}; earlier soft failures=$softFailures")
            Thread.sleep(250)
        }
    }

    private fun startBob(vararg roomIds: String) {
        val root = context.filesDir.resolve("matrix-details-bob")
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
            client.login(BOB, DEMO_PASSWORD, "Send details test", null)
            val sync = client.syncService().finish()
            bobSync = sync
            sync.start()
            for (roomId in roomIds) bobRooms[roomId] = client.joinRoomById(roomId)
        }
    }

    private companion object {
        const val CAROL = "@carol:send.test"
    }
}
