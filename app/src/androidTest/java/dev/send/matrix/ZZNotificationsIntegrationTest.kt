package dev.send.matrix

import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.os.Bundle
import android.service.notification.StatusBarNotification
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.UiDevice
import dev.send.matrix.notifications.MessageNotifier
import dev.send.matrix.notifications.NotificationRouter
import dev.send.matrix.notifications.PushRegistration
import dev.send.matrix.notifications.PushedEvent
import dev.send.matrix.notifications.discoverMatrixGateway
import dev.send.matrix.notifications.parsePushPayload
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.ClientBuilder
import org.matrix.rustcomponents.sdk.EventOrTransactionId
import org.matrix.rustcomponents.sdk.MessageType
import org.matrix.rustcomponents.sdk.MsgLikeKind
import org.matrix.rustcomponents.sdk.SlidingSyncVersionBuilder
import org.matrix.rustcomponents.sdk.SqliteStoreBuilder
import org.matrix.rustcomponents.sdk.SyncService
import org.matrix.rustcomponents.sdk.TaskHandle
import org.matrix.rustcomponents.sdk.TextMessageContent
import org.matrix.rustcomponents.sdk.Timeline
import org.matrix.rustcomponents.sdk.TimelineDiff
import org.matrix.rustcomponents.sdk.TimelineItem
import org.matrix.rustcomponents.sdk.TimelineItemContent
import org.matrix.rustcomponents.sdk.TimelineListener

/**
 * Phase 2 acceptance against the local Synapse (after ZZAccountSecurityIntegrationTest, whose signed-in Alice it
 * continues): Bob (an independent SDK client) messages Alice while the app is in the background, and the
 * notification is checked through NotificationManager and driven through its own PendingIntents.
 */
@RunWith(AndroidJUnit4::class)
class ZZNotificationsIntegrationTest {
    @get:Rule(order = 0)
    val notificationPermission: GrantPermissionRule = GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val repository get() = MatrixRepository.get(context)
    private val notificationManager get() = context.getSystemService(NotificationManager::class.java)

    private var bobClient: Client? = null
    private var bobSync: SyncService? = null
    private var bobTimeline: Timeline? = null
    private var bobTimelineHandle: TaskHandle? = null
    /** Message bodies Bob's client decrypted, with their event IDs once the homeserver assigned them. */
    private val bobSeen = ConcurrentHashMap<String, String>()

    @After
    fun closeBob() {
        bobTimelineHandle?.close()
        bobTimeline?.close()
        bobSync?.close()
        bobClient?.close()
    }

    @Test
    fun backgroundMessagesNotifyAndTheirActionsWork() {
        composeRule.signInAsAliceIfNeeded()
        waitFor("Alice's session", 60_000, { "account=${repository.account.value}" }) { repository.account.value != null }
        val roomId = runBlocking { repository.createEncryptedGroup(GROUP_NAME, listOf(BOB)) }
        startBob(roomId)
        waitFor("the group in Alice's list", 60_000, ::roomsState) { repository.joinedRooms.value.any { it.id == roomId && it.encrypted } }
        val stamp = System.currentTimeMillis()

        // 1. Backgrounded: Bob's encrypted message becomes a decrypted MessagingStyle notification.
        goHome()
        val first = "Notification check $stamp"
        bobSends(first)
        val notification = waitForMessage(roomId, first)
        val firstMessage = lastMessage(notification)
        assertEquals(BOB, firstMessage.person?.key)
        assertTrue("sender name was ${firstMessage.person?.name}", firstMessage.person?.name.toString().contains("Bob", ignoreCase = true))
        val style = checkNotNull(NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification.notification))
        assertTrue(style.isGroupConversation)
        assertEquals(GROUP_NAME, style.conversationTitle?.toString())
        assertEquals(MessageNotifier.CHANNEL_MESSAGES, notification.notification.channelId)
        assertEquals(roomId, notification.notification.shortcutId)
        // Messages-style bubble: the notification carries bubble metadata whose target is the bubble's own document
        // of this conversation (whether the system lets it float is the user's per-app choice).
        val bubble = checkNotNull(notification.notification.bubbleMetadata) { "No bubble metadata" }
        assertTrue("bubble height ${bubble.desiredHeight}", bubble.desiredHeight > 0)
        assertEquals(false, bubble.autoExpandBubble)
        // Lock screen: private, with a public version that has no message text.
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.notification.visibility)
        val publicText = checkNotNull(notification.notification.publicVersion).extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertTrue(publicText, !publicText.contains(first))
        // Visual audit: the real notification in the expanded shade.
        val device = androidx.test.uiautomator.UiDevice.getInstance(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation())
        device.openNotification()
        Thread.sleep(1_500)
        composeRule.auditShot("notification")
        device.executeShellCommand("cmd statusbar collapse")

        // 2. Inline reply: lands encrypted on Bob's client and joins the conversation in the notification.
        val reply = "Replied from the notification $stamp"
        reply(notification, reply)
        waitFor("Bob to decrypt the reply", 60_000, { "bob=${bobSeen.keys}" }) { bobSeen.containsKey(reply) }
        waitFor("the reply in the notification", 30_000, { notificationState(roomId) }) {
            conversationNotification(roomId)?.let { lastMessage(it) }?.let { it.text.toString() == reply && it.person?.key != BOB } == true
        }

        // 3. Mark as read: the receipt clears the room's unread count and the notification goes away.
        val second = "Mark me as read $stamp"
        bobSends(second)
        val unreadNotification = waitForMessage(roomId, second)
        waitFor("the unread badge", 60_000, ::roomsState) { (room(roomId)?.unreadCount ?: 0) > 0 }
        action(unreadNotification, Notification.Action.SEMANTIC_ACTION_MARK_AS_READ).actionIntent.send()
        waitFor("the read receipt", 60_000, { "${roomsState()} ${notificationState(roomId)}" }) {
            conversationNotification(roomId) == null && room(roomId)?.unreadCount == 0
        }

        // 4. Tapping opens that conversation in the app and clears its notification; while it's on screen, no notification.
        val third = "Open the conversation $stamp"
        bobSends(third)
        val openNotification = waitForMessage(roomId, third)
        openNotification.notification.contentIntent.send(
            context, 0, null, null, null, null,
            ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                .toBundle(),
        )
        // Compose waits: the app's UI has to run (and recompose) to act on the tapped intent.
        waitForUi("the tapped conversation on screen", 60_000, ::conversationState) {
            NotificationRouter.visibleRoomId == roomId && composeRule.onAllNodesWithText(third).fetchSemanticsNodes().isNotEmpty()
        }
        waitFor("the opened conversation's notification to clear", 10_000, { notificationState(roomId) }) { conversationNotification(roomId) == null }
        val fourth = "Seen in the app $stamp"
        bobSends(fourth)
        waitForUi("Bob's message in the open conversation", 60_000, ::conversationState) {
            composeRule.onAllNodesWithText(fourth).fetchSemanticsNodes().isNotEmpty()
        }
        Thread.sleep(QUIET_PERIOD_MS)
        assertNull(notificationState(roomId), conversationNotification(roomId))

        // 5. Mute from the conversation's ⋮ menu: Bob's next message syncs but doesn't notify; unmuting restores it.
        composeRule.onNodeWithContentDescription("More options").performClick()
        composeRule.onNodeWithText("Mute").performClick()
        composeRule.waitUntil(timeoutMillis = 30_000) { roomId in repository.mutedRoomIds.value }
        goHome()
        val muted = "Muted message $stamp"
        bobSends(muted)
        waitFor("the muted message to sync", 60_000, ::roomsState) { room(roomId)?.lastMessage == muted }
        Thread.sleep(QUIET_PERIOD_MS)
        assertNull(notificationState(roomId), conversationNotification(roomId))
        runBlocking { repository.setRoomMuted(roomId, muted = false) }
        assertTrue(roomId !in repository.mutedRoomIds.value)
        // The removed push rule reaches this device's sync before Bob's next message.
        Thread.sleep(PUSH_RULE_SYNC_MS)
        val unmuted = "Unmuted message $stamp"
        bobSends(unmuted)
        val unmutedNotification = waitForMessage(roomId, unmuted)

        // 6. The bubble: its intent opens BubbleActivity on this conversation alone (the system would float it);
        // the bubbled conversation shows Bob's message and claims the timeline, and closing it collapses the bubble.
        val bubbleIntent = MessageNotifier.bubbleIntent(context, roomId)
        assertEquals(BubbleActivity::class.java.name, bubbleIntent.component?.className)
        assertNotNull(unmutedNotification.notification.bubbleMetadata)
        androidx.test.core.app.ActivityScenario.launch<BubbleActivity>(bubbleIntent).use { scenario ->
            waitFor("the bubbled conversation", 60_000, { "visible=${NotificationRouter.visibleRoomId} state=${scenario.state}" }) {
                scenario.state == Lifecycle.State.RESUMED && NotificationRouter.visibleRoomId == roomId &&
                    repository.messages.value.any { it.text == unmuted }
            }
            assertNull(notificationState(roomId), conversationNotification(roomId))
            waitFor("the bubbled conversation on screen", 30_000, { "found=${bubbleShows(unmuted)}" }) { bubbleShows(unmuted) }
        }
    }

    /** Whether the bubble's window shows [text] (the main activity's compose rule sees every window's tree). */
    private fun bubbleShows(text: String): Boolean =
        runCatching { composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false)

    @Test
    fun unifiedPushEndpointBecomesAPusherAndAPushedEventNotifiesFromAColdStart() {
        composeRule.signInAsAliceIfNeeded()
        waitFor("Alice's session", 60_000, { "account=${repository.account.value}" }) { repository.account.value != null }

        // Gateway discovery: a push server announcing a Matrix gateway is used; any other gets the public gateway.
        GatewayServer(matrixGateway = true).use { server ->
            GatewayServer(matrixGateway = false).use { plain ->
                val gateway = "http://127.0.0.1:${server.port}/_matrix/push/v1/notify"
                val endpoint = "http://127.0.0.1:${server.port}/upSendCi${System.currentTimeMillis()}?up=1"
                assertEquals(gateway, runBlocking { discoverMatrixGateway(endpoint) })
                assertEquals(PushRegistration.DEFAULT_MATRIX_GATEWAY, runBlocking { discoverMatrixGateway("http://127.0.0.1:${plain.port}/topic?up=1") })

                // New endpoint → Client.setPusher; the homeserver lists it for Alice.
                runBlocking { PushRegistration.onNewEndpoint(context, endpoint) }
                assertTrue(PushRegistration.status.value.toString(), PushRegistration.status.value.pushActive)
                val token = synapseLogin()
                try {
                    val pusher = checkNotNull(pushers(token).firstOrNull { it.getString("pushkey") == endpoint }) { "pushers=${pushers(token)}" }
                    assertEquals(MatrixRepository.PUSHER_APP_ID, pusher.getString("app_id"))
                    assertEquals("http", pusher.getString("kind"))
                    assertEquals("Send", pusher.getString("app_display_name"))
                    assertEquals(gateway, pusher.getJSONObject("data").getString("url"))
                    assertEquals("event_id_only", pusher.getJSONObject("data").getString("format"))

                    // Unregistering deletes it.
                    runBlocking { PushRegistration.onUnregistered(context) }
                    assertTrue(pushers(token).none { it.getString("pushkey") == endpoint })
                    assertTrue(!PushRegistration.status.value.pushActive)
                } finally {
                    synapseLogout(token)
                }
            }
        }

        // A pushed event (gateway payload) is fetched and decrypted with NotificationClient after process death.
        val roomId = runBlocking { repository.createEncryptedGroup(PUSH_GROUP_NAME, listOf(BOB)) }
        startBob(roomId)
        waitFor("the group in Alice's list", 60_000, ::roomsState) { repository.joinedRooms.value.any { it.id == roomId && it.encrypted } }
        val warmUp = "Before the restart ${System.currentTimeMillis()}"
        bobSends(warmUp)
        waitFor("Alice to sync Bob's first message", 60_000, ::roomsState) { room(roomId)?.lastMessage == warmUp }
        goHome()
        runBlocking { repository.close() }
        assertNull(repository.account.value)

        val pushedBody = "Pushed while Send was closed ${System.currentTimeMillis()}"
        val eventId = bobSends(pushedBody)
        val payload = """{"notification":{"event_id":"$eventId","room_id":"$roomId","counts":{"unread":1},"prio":"high","devices":[{"app_id":"${MatrixRepository.PUSHER_APP_ID}","pushkey":"https://push.example/up"}]}}"""
        assertEquals(PushedEvent(roomId, eventId), parsePushPayload(payload.toByteArray()))
        // What PushEventWorker runs for a UnifiedPush message.
        assertTrue(runBlocking { NotificationRouter.fetchAndRoute(context, roomId, eventId) })
        val notification = waitForMessage(roomId, pushedBody)
        assertEquals(BOB, lastMessage(notification).person?.key)

        // Reply from the same cold session, sync still stopped.
        val coldReply = "Cold reply ${System.currentTimeMillis()}"
        reply(notification, coldReply)
        waitFor("Bob to decrypt the cold reply", 60_000, { "bob=${bobSeen.keys}" }) { bobSeen.containsKey(coldReply) }
    }

    private fun goHome() {
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressHome()
        waitFor("the activity to stop", 10_000, { "state=${activityState()}" }) {
            activityState() == Lifecycle.State.CREATED && NotificationRouter.visibleRoomId == null
        }
    }

    private fun reply(notification: StatusBarNotification, text: String) {
        val action = action(notification, Notification.Action.SEMANTIC_ACTION_REPLY)
        val results = Bundle().apply { putCharSequence(MessageNotifier.REMOTE_INPUT_REPLY, text) }
        val fillIn = Intent().also { android.app.RemoteInput.addResultsToIntent(action.remoteInputs, it, results) }
        action.actionIntent.send(context, 0, fillIn)
    }

    private fun action(notification: StatusBarNotification, semanticAction: Int): Notification.Action =
        checkNotNull(notification.notification.actions?.firstOrNull { it.semanticAction == semanticAction }) { "No action $semanticAction" }

    private fun conversationNotification(roomId: String): StatusBarNotification? =
        notificationManager.activeNotifications.firstOrNull { it.tag == roomId && it.id == MessageNotifier.NOTIFICATION_ID }

    private fun lastMessage(notification: StatusBarNotification): NotificationCompat.MessagingStyle.Message =
        checkNotNull(NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification.notification)?.messages?.lastOrNull())

    private fun waitForMessage(roomId: String, body: String): StatusBarNotification {
        waitFor("a notification showing \"$body\"", 90_000, { notificationState(roomId) }) {
            conversationNotification(roomId)?.let { lastMessage(it).text.toString() == body } == true
        }
        return checkNotNull(conversationNotification(roomId))
    }

    private fun notificationState(roomId: String): String {
        val active = notificationManager.activeNotifications.map { "${it.tag}/${it.id}" }
        val messages = conversationNotification(roomId)?.let { notification ->
            NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification.notification)?.messages
                ?.map { "${it.person?.key}: ${it.text}" }
        }
        return "active=$active messages=$messages muted=${repository.mutedRoomIds.value} visible=${NotificationRouter.visibleRoomId}"
    }

    private fun room(roomId: String): MatrixRoom? = repository.joinedRooms.value.firstOrNull { it.id == roomId }

    private fun roomsState(): String = "rooms=${repository.joinedRooms.value.map { "${it.name}:${it.unreadCount}:${it.lastMessage}" }}"

    private fun conversationState(): String =
        "visible=${NotificationRouter.visibleRoomId} activity=${activityState()} " +
            "messages=${repository.messages.value.map { "${it.sender}:${it.decryptionFailure ?: it.text}" }}"

    private fun activityState(): Lifecycle.State? = runCatching { composeRule.activityRule.scenario.state }.getOrNull()

    /** Compose's waitUntil (which keeps the app's UI composing), reporting app state on timeout. */
    private fun waitForUi(what: String, timeoutMillis: Long, state: () -> String, condition: () -> Boolean) {
        try {
            composeRule.waitUntil(timeoutMillis = timeoutMillis, condition = condition)
        } catch (timeout: ComposeTimeoutException) {
            throw AssertionError("Timed out waiting for $what; ${state()}", timeout)
        }
    }

    /** Polls off the main thread: the activity may be stopped, so Compose's idling isn't used here. */
    private fun waitFor(what: String, timeoutMillis: Long, state: () -> String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("Timed out waiting for $what; ${state()}")
            Thread.sleep(250)
        }
    }

    private fun startBob(roomId: String) {
        val root = context.filesDir.resolve("matrix-notifications-bob")
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
            client.login(BOB, DEMO_PASSWORD, "Send notifications test", null)
            val sync = client.syncService().finish()
            bobSync = sync
            sync.start()
            val room = client.joinWhenSynced(roomId)
            val timeline = room.timeline()
            bobTimeline = timeline
            bobTimelineHandle = timeline.addListener(object : TimelineListener {
                override fun onUpdate(diffs: List<TimelineDiff>) {
                    for (diff in diffs) {
                        for (item in timelineItems(diff)) {
                            val event = item.asEvent() ?: continue
                            val content = event.content as? TimelineItemContent.MsgLike ?: continue
                            val text = ((content.content.kind as? MsgLikeKind.Message)?.content?.msgType as? MessageType.Text)?.content?.body ?: continue
                            val eventId = (event.eventOrTransactionId as? EventOrTransactionId.EventId)?.eventId
                            if (eventId != null) bobSeen[text] = eventId else bobSeen.putIfAbsent(text, "")
                        }
                    }
                }
            })
        }
    }

    /** Bob sends [body] and returns its event ID once the homeserver has it. */
    private fun bobSends(body: String): String {
        val timeline = checkNotNull(bobTimeline)
        runBlocking(Dispatchers.IO) {
            timeline.send(checkNotNull(timeline.createMessageContent(MessageType.Text(TextMessageContent(body, null)))))
        }
        waitFor("Bob's message to be sent", 60_000, { "bob=${bobSeen}" }) { bobSeen[body]?.startsWith("$") == true }
        return checkNotNull(bobSeen[body])
    }

    private fun timelineItems(diff: TimelineDiff): List<TimelineItem> = when (diff) {
        is TimelineDiff.Reset -> diff.values
        is TimelineDiff.Append -> diff.values
        is TimelineDiff.PushBack -> listOf(diff.value)
        is TimelineDiff.PushFront -> listOf(diff.value)
        is TimelineDiff.Insert -> listOf(diff.value)
        is TimelineDiff.Set -> listOf(diff.value)
        else -> emptyList()
    }

    /** A separate password login for the client-server API (pushers are per account, not per device). */
    private fun synapseLogin(): String {
        val body = JSONObject()
            .put("type", "m.login.password")
            .put("identifier", JSONObject().put("type", "m.id.user").put("user", ALICE))
            .put("password", DEMO_PASSWORD)
            .put("initial_device_display_name", "Send notifications test (REST)")
        return JSONObject(http("POST", "/_matrix/client/v3/login", null, body.toString())).getString("access_token")
    }

    private fun synapseLogout(token: String) {
        runCatching { http("POST", "/_matrix/client/v3/logout", token, "{}") }
    }

    private fun pushers(token: String): List<JSONObject> {
        val array = JSONObject(http("GET", "/_matrix/client/v3/pushers", token, null)).getJSONArray("pushers")
        return List(array.length()) { array.getJSONObject(it) }
    }

    private fun http(method: String, path: String, token: String?, body: String?): String {
        val connection = URL("$SYNAPSE_URL$path").openConnection() as HttpURLConnection
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

    /** A push server on the emulator's loopback that does (or doesn't) announce a UnifiedPush Matrix gateway. */
    private class GatewayServer(private val matrixGateway: Boolean) : AutoCloseable {
        private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = socket.localPort

        init {
            Thread {
                while (!socket.isClosed) {
                    val client = try {
                        socket.accept()
                    } catch (_: IOException) {
                        break
                    }
                    client.use { connection ->
                        val reader = connection.getInputStream().bufferedReader()
                        val requestLine = reader.readLine().orEmpty()
                        while (!reader.readLine().isNullOrEmpty()) Unit
                        val announce = matrixGateway && requestLine.startsWith("GET /_matrix/push/v1/notify ")
                        val payload = if (announce) """{"unifiedpush":{"gateway":"matrix"}}""" else """{"errcode":"M_NOT_FOUND"}"""
                        val status = if (announce) "200 OK" else "404 Not Found"
                        connection.getOutputStream().write(
                            ("HTTP/1.1 $status\r\nContent-Type: application/json\r\nContent-Length: ${payload.toByteArray().size}\r\n" +
                                "Connection: close\r\n\r\n$payload").toByteArray(),
                        )
                    }
                }
            }.apply { isDaemon = true }.start()
        }

        override fun close() = socket.close()
    }

    private companion object {
        const val GROUP_NAME = "Notification check"
        const val PUSH_GROUP_NAME = "Push check"
        /** How long a suppressed notification is given to (wrongly) appear. */
        const val QUIET_PERIOD_MS = 5_000L
        const val PUSH_RULE_SYNC_MS = 3_000L
    }
}
