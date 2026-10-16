package dev.send.matrix

import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.UiDevice
import dev.send.matrix.notifications.IncomingCalls
import dev.send.matrix.notifications.NotificationRouter
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.rustcomponents.sdk.CallDeclineListener
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.ClientBuilder
import org.matrix.rustcomponents.sdk.EventOrTransactionId
import org.matrix.rustcomponents.sdk.MessageLikeEventContent
import org.matrix.rustcomponents.sdk.NotificationEvent
import org.matrix.rustcomponents.sdk.NotificationItem
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.RtcCallIntent
import org.matrix.rustcomponents.sdk.RtcNotificationType
import org.matrix.rustcomponents.sdk.RtcTransport
import org.matrix.rustcomponents.sdk.SlidingSyncVersionBuilder
import org.matrix.rustcomponents.sdk.SqliteStoreBuilder
import org.matrix.rustcomponents.sdk.SyncNotificationListener
import org.matrix.rustcomponents.sdk.SyncService
import org.matrix.rustcomponents.sdk.TaskHandle
import org.matrix.rustcomponents.sdk.Timeline
import org.matrix.rustcomponents.sdk.TimelineDiff
import org.matrix.rustcomponents.sdk.TimelineEventContent
import org.matrix.rustcomponents.sdk.TimelineItemContent
import org.matrix.rustcomponents.sdk.TimelineListener

/**
 * Phase 3 acceptance against the local Synapse + LiveKit + lk-jwt-service (dev/matrix/compose.yaml), continuing
 * the signed-in Alice: Bob (an in-process SDK client) rings Alice's DM the way Element Call does (a call
 * membership, then an encrypted `org.matrix.msc4075.rtc.notification` RING), Alice's device rings with a
 * full-screen CallStyle notification whose Decline and Answer are fired; then Alice calls Bob from the
 * conversation and Bob declines. Media is not asserted: Bob has no media stack.
 */
@RunWith(AndroidJUnit4::class)
class ZZRtcCallsIntegrationTest {
    @get:Rule(order = 0)
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        android.Manifest.permission.POST_NOTIFICATIONS,
        android.Manifest.permission.RECORD_AUDIO,
    )

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val repository get() = MatrixRepository.get(context)
    private val notificationManager get() = context.getSystemService(NotificationManager::class.java)

    private var bobClient: Client? = null
    private var bobSync: SyncService? = null
    private var bobRoom: Room? = null
    private var bobTimeline: Timeline? = null
    private val bobHandles = mutableListOf<TaskHandle>()
    private var bobMembershipKey: String? = null
    /** RTC notifications Bob's timeline showed: event ID → sender. */
    private val bobTimelineRings = ConcurrentHashMap<String, String>()
    /** RTC notifications Bob's sync notification handler reported (i.e. that would ring Bob): event ID → "sender type intent". */
    private val bobRung = ConcurrentHashMap<String, String>()
    /** Declines Bob's client observed: ring event ID → decliner. */
    private val bobSawDeclines = ConcurrentHashMap<String, String>()

    @After
    fun tearDown() {
        repository.activeCallRoomId()?.let { instrumentation.runOnMainSync { repository.callSession().hangUp() } }
        IncomingCalls.ringing.value.keys.forEach { IncomingCalls.stop(context, it) }
        runCatching { bobLeavesCall() }
        bobHandles.forEach { it.close() }
        bobTimeline?.close()
        bobRoom?.close()
        bobSync?.close()
        bobClient?.close()
    }

    @Test
    fun aliceDiscoversTheLiveKitTransport() {
        composeRule.signInAsAliceIfNeeded()
        waitFor("Alice's session", 60_000, { "account=${repository.account.value}" }) { repository.account.value != null }
        val transports = runBlocking { repository.rtcTransports() }
        assertTrue(
            "transports=$transports",
            transports.any { it is RtcTransport.LiveKit && it.serviceUrl == LIVEKIT_SERVICE },
        )
        // The same list, as Element Call's discovery reads it through Send's call transport.
        val advertised = JSONObject(rtcTransportsJson(transports)).getJSONArray("rtc_transports")
        assertEquals("livekit", advertised.getJSONObject(0).getString("type"))
        assertEquals(LIVEKIT_SERVICE, advertised.getJSONObject(0).getString("livekit_service_url"))
    }

    @Test
    fun bobRingsAliceAndAliceCallsBob() {
        composeRule.signInAsAliceIfNeeded()
        waitFor("Alice's session", 60_000, { "account=${repository.account.value}" }) { repository.account.value != null }
        // The CI emulator grants it to an adb-installed app, but make the full-screen path certain.
        shell("appops set ${context.packageName} USE_FULL_SCREEN_INTENT allow")
        assertTrue(IncomingCalls.canRingFullScreen(context))
        val roomId = runBlocking { repository.createEncryptedDirectRoom(BOB) }
        startBob(roomId)
        waitFor("the DM in Alice's list", 60_000, ::roomsState) { repository.joinedRooms.value.any { it.id == roomId && it.encrypted } }
        waitFor("Bob in the DM", 60_000, ::roomsState) {
            runBlocking { repository.sdkRoom(roomId)?.use { it.roomInfo().joinedMembersCount.toLong() } ?: 0L } >= 2L
        }

        // 1. Bob rings; with Send in the background Alice gets the full-screen CallStyle ring. Decline reaches Bob.
        goHome()
        val firstRing = bobRings(bobJoinsCall())
        val ringing = waitForRing(roomId)
        val notification = ringing.notification
        assertEquals(Notification.CATEGORY_CALL, notification.category)
        assertEquals(IncomingCalls.CHANNEL_CALLS, notification.channelId)
        assertNotNull("full-screen intent", notification.fullScreenIntent)
        assertTrue("insistent ring", notification.flags and Notification.FLAG_INSISTENT != 0)
        assertEquals("android.app.Notification\$CallStyle", notification.extras.getString(Notification.EXTRA_TEMPLATE))
        assertEquals(Notification.CallStyle.CALL_TYPE_INCOMING, notification.extras.getInt(Notification.EXTRA_CALL_TYPE))
        val caller = checkNotNull(notification.extras.getParcelable(Notification.EXTRA_CALL_PERSON, android.app.Person::class.java))
        assertEquals(BOB, caller.key)
        assertTrue("caller ${caller.name}", caller.name.toString().contains("Bob", ignoreCase = true))
        assertEquals(firstRing, IncomingCalls.ringing.value[roomId]?.eventId)
        subscribeBobToDeclines(firstRing)
        intentExtra(ringing, Notification.EXTRA_DECLINE_INTENT).send()
        waitFor("Bob to see Alice decline", 60_000, { "declines=$bobSawDeclines" }) { bobSawDeclines[firstRing] == ALICE }
        assertNull(callNotification(roomId))
        assertTrue(roomId !in IncomingCalls.ringing.value)
        bobLeavesCall()

        // 2. Bob rings again; Answer opens Send, which joins the room's MatrixRTC session through Element Call.
        val secondRing = bobRings(bobJoinsCall())
        val answered = waitForRing(roomId)
        assertEquals(secondRing, IncomingCalls.ringing.value[roomId]?.eventId)
        intentExtra(answered, Notification.EXTRA_ANSWER_INTENT).send(
            context, 0, null, null, null, null,
            ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                .toBundle(),
        )
        waitForUi("Alice's call membership in the room", 120_000, ::callState) {
            repository.activeCallRoomId() == roomId && ALICE in checkNotNull(bobRoom).activeRoomCallParticipants()
        }
        assertNull(callNotification(roomId))
        waitFor("Alice's list to show the call", 60_000, ::roomsState) {
            repository.joinedRooms.value.firstOrNull { it.id == roomId }?.let { it.callActive && ALICE in it.callParticipants } == true
        }
        // Media is best effort here (Bob publishes nothing); the log records how far the call got.
        runCatching {
            waitForUi("media", 30_000, ::callState) {
                repository.callSession().controller.state.value?.connection == io.element.android.call.api.ElementCallConnection.Connected
            }
        }
        Log.i(TAG, "Answered call: ${callState()}")
        instrumentation.runOnMainSync { repository.callSession().hangUp() }
        waitForUi("Alice to leave the call", 60_000, ::callState) {
            repository.activeCallRoomId() == null && ALICE !in checkNotNull(bobRoom).activeRoomCallParticipants()
        }
        bobLeavesCall()
        waitFor("the call to end for Bob", 60_000, ::callState) { !checkNotNull(bobRoom).hasActiveRoomCall() }

        // 3. Alice calls from the conversation: Element Call rings Bob; Bob declines; Alice's call ends with "Call declined".
        waitForUi("the DM conversation", 60_000, ::callState) {
            NotificationRouter.visibleRoomId == roomId &&
                composeRule.onAllNodesWithContentDescription("Voice call").fetchSemanticsNodes().isNotEmpty()
        }
        // Joining Bob's running session rang nobody: Element Call only rings when nobody else is in the call.
        assertTrue(
            "answering re-rang Bob: rung=$bobRung timeline=$bobTimelineRings",
            bobRung.values.none { it.startsWith(ALICE) } && bobTimelineRings.values.none { it == ALICE },
        )
        // Only the ring of the call placed below counts.
        bobRung.clear()
        val placedAt = System.currentTimeMillis()
        composeRule.onNodeWithContentDescription("Voice call").performClick()
        waitForUi("Bob's client to ring for Alice's call", 120_000, { "${callState()} rung=$bobRung timeline=$bobTimelineRings" }) {
            bobRung.values.any { it.startsWith("$ALICE RING") }
        }
        val aliceRing = bobRung.entries.first { it.value.startsWith("$ALICE RING") }.key
        assertTrue(bobRung.getValue(aliceRing), bobRung.getValue(aliceRing).endsWith("AUDIO"))
        waitForUi("Calling…", 30_000, ::callState) {
            composeRule.onAllNodes(hasText("Calling", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        Log.i(TAG, "Alice's ring $aliceRing reached Bob ${System.currentTimeMillis() - placedAt} ms after the tap")
        runBlocking(Dispatchers.IO) { checkNotNull(bobRoom).declineCall(aliceRing) }
        waitForUi("Alice's call to end as declined", 60_000, ::callState) {
            repository.activeCallRoomId() == null &&
                composeRule.onAllNodesWithText("Call declined").fetchSemanticsNodes().isNotEmpty()
        }

        // 4. The conversation shows the three calls as call rows from the real events. The first ring can sit before
        // the page the timeline loaded when Answer opened the conversation (call memberships, declines and the later
        // rings fill it), so older history is loaded the way a user would: ⋮ → Load older messages.
        var olderLoads = 0
        while (repository.messages.value.none { it.id == firstRing } && olderLoads++ < 5) {
            composeRule.onNodeWithContentDescription("More options").performClick()
            composeRule.onNodeWithText("Load older messages").performClick()
            runCatching {
                composeRule.waitUntil(timeoutMillis = 10_000) { repository.messages.value.any { it.id == firstRing } }
            }
        }
        waitForUi("call rows in the conversation", 60_000, ::callState) {
            val messages = repository.messages.value.mapNotNull { it.call }
            messages.count { it.declined } >= 2 && messages.any { it.answered && it.durationMillis != null } &&
                composeRule.onAllNodes(hasText("Voice call · ", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        val rows = repository.messages.value.filter { it.call != null }
        assertEquals(listOf(firstRing, secondRing, aliceRing), rows.map { it.id }.filter { it in setOf(firstRing, secondRing, aliceRing) })
        assertTrue(rows.first { it.id == firstRing }.call!!.declined)
        assertTrue(rows.first { it.id == aliceRing }.call!!.let { it.declined && it.outgoing })
    }

    private fun startBob(roomId: String) {
        val root = context.filesDir.resolve("matrix-calls-bob")
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
            client.login(BOB, DEMO_PASSWORD, "Send calls test", null)
            // What would ring Bob's phone: the push-rule-filtered notifications of Bob's sync.
            client.registerNotificationHandler(object : SyncNotificationListener {
                override fun onNotification(notification: NotificationItem, roomId: String) {
                    val event = (notification.event as? NotificationEvent.Timeline)?.event ?: return
                    try {
                        val content = (event.content() as? TimelineEventContent.MessageLike)?.content as? MessageLikeEventContent.RtcNotification ?: return
                        val type = if (content.notificationType == RtcNotificationType.RING) "RING" else "NOTIFICATION"
                        val intent = if (content.callIntent == RtcCallIntent.VIDEO) "VIDEO" else "AUDIO"
                        bobRung[event.eventId()] = "${event.senderId()} $type $intent"
                    } finally {
                        event.close()
                    }
                }
            })
            val sync = client.syncService().finish()
            bobSync = sync
            sync.start()
            val room = client.joinWhenSynced(roomId)
            bobRoom = room
            val timeline = room.timeline()
            bobTimeline = timeline
            bobHandles += timeline.addListener(object : TimelineListener {
                override fun onUpdate(diffs: List<TimelineDiff>) {
                    for (item in diffs.flatMap(::timelineDiffItems)) {
                        val event = item.asEvent() ?: continue
                        if (event.content !is TimelineItemContent.RtcNotification) continue
                        val eventId = (event.eventOrTransactionId as? EventOrTransactionId.EventId)?.eventId ?: continue
                        bobTimelineRings[eventId] = event.sender
                    }
                }
            })
        }
    }

    /** Bob's device publishes an Element Call (room-state dialect) membership in the DM; returns its event ID. */
    private fun bobJoinsCall(): String {
        val client = checkNotNull(bobClient)
        val room = checkNotNull(bobRoom)
        val device = client.deviceId()
        val content = JSONObject()
            .put("application", "m.call")
            .put("call_id", "")
            .put("scope", "m.room")
            .put("device_id", device)
            .put("membershipID", "$BOB:$device")
            .put("created_ts", System.currentTimeMillis())
            .put("expires", 14_400_000)
            .put("focus_active", JSONObject().put("type", "livekit").put("focus_selection", "multi_sfu"))
            .put(
                "foci_preferred",
                JSONArray().put(JSONObject().put("type", "livekit").put("livekit_service_url", LIVEKIT_SERVICE).put("livekit_alias", room.id())),
            )
            .put("m.call.intent", "audio")
        val key = "_${BOB}_${device}_m.call"
        bobMembershipKey = key
        return runBlocking(Dispatchers.IO) { room.sendStateEventRaw(CALL_MEMBER, key, content.toString()) }
    }

    private fun bobLeavesCall() {
        val key = bobMembershipKey ?: return
        bobMembershipKey = null
        runBlocking(Dispatchers.IO) { checkNotNull(bobRoom).sendStateEventRaw(CALL_MEMBER, key, "{}") }
    }

    /** Bob's encrypted MSC4075 RING for his membership [memberEventId], shaped as Element Call's core sends it. */
    private fun bobRings(memberEventId: String): String {
        val now = System.currentTimeMillis()
        val device = checkNotNull(bobClient).deviceId()
        val content = JSONObject()
            .put(
                "application",
                JSONObject().put("type", "m.call").put("notification_type", "ring").put("sender_ts", now)
                    .put("lifetime", RING_LIFETIME_MS).put("device_id", device).put("m.call.intent", "audio"),
            )
            .put("notification_type", "ring")
            .put("sender_ts", now)
            .put("lifetime", RING_LIFETIME_MS)
            .put("m.call.intent", "audio")
            .put("m.text", JSONArray().put(JSONObject().put("body", "Session started by $BOB")))
            .put("m.mentions", JSONObject().put("user_ids", JSONArray()).put("room", true))
            .put("m.relates_to", JSONObject().put("rel_type", "m.reference").put("event_id", memberEventId))
        return runBlocking(Dispatchers.IO) { checkNotNull(bobRoom).sendRaw("org.matrix.msc4075.rtc.notification", content.toString()) }
    }

    private fun subscribeBobToDeclines(ringEventId: String) {
        bobHandles += checkNotNull(bobRoom).subscribeToCallDeclineEvents(ringEventId, object : CallDeclineListener {
            override fun call(userId: String) {
                bobSawDeclines[ringEventId] = userId
            }
        })
    }

    private fun callNotification(roomId: String): StatusBarNotification? =
        notificationManager.activeNotifications.firstOrNull { it.tag == roomId && it.id == IncomingCalls.NOTIFICATION_ID }

    private fun waitForRing(roomId: String): StatusBarNotification {
        waitFor("Alice's phone to ring", 90_000, { "${callState()} active=${notificationManager.activeNotifications.map { "${it.tag}/${it.id}" }}" }) {
            callNotification(roomId) != null
        }
        return checkNotNull(callNotification(roomId))
    }

    private fun intentExtra(notification: StatusBarNotification, key: String): PendingIntent =
        checkNotNull(notification.notification.extras.getParcelable(key, PendingIntent::class.java)) { "No $key" }

    private fun goHome() {
        UiDevice.getInstance(instrumentation).pressHome()
        waitFor("the activity to stop", 10_000, { "state=${runCatching { composeRule.activityRule.scenario.state }.getOrNull()}" }) {
            runCatching { composeRule.activityRule.scenario.state }.getOrNull() == Lifecycle.State.CREATED
        }
    }

    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().readText()
        }
    }

    private fun roomsState(): String =
        "rooms=${repository.joinedRooms.value.map { "${it.name}:${it.direct}:${it.callActive}:${it.callParticipants}" }}"

    private fun callState(): String {
        val snapshot = runCatching { repository.callSession().controller.state.value }.getOrNull()
        val bob = runCatching { checkNotNull(bobRoom).activeRoomCallParticipants() }.getOrNull()
        return "call=${snapshot?.callData?.roomId}/${snapshot?.connection} bobSees=$bob ringing=${IncomingCalls.ringing.value.keys} " +
            "calls=${repository.messages.value.mapNotNull { message -> message.call?.let { "${message.id}:${it.label}" } }}"
    }

    /** Compose's waitUntil (which keeps the app's UI composing), reporting state on timeout. */
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

    private companion object {
        const val TAG = "ZZRtcCalls"
        const val LIVEKIT_SERVICE = "http://10.0.2.2:8090"
        const val CALL_MEMBER = "org.matrix.msc3401.call.member"
        /** Longer than Element Call's 30 s so a slow emulator still sees the ring live. */
        const val RING_LIFETIME_MS = 60_000L
    }
}
