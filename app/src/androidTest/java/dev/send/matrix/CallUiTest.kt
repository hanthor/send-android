package dev.send.matrix

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.rustcomponents.sdk.RtcTransport

/** Fixture coverage for the calling surfaces; live calls run in ZZRtcCallsIntegrationTest. */
@RunWith(AndroidJUnit4::class)
class CallUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun call(id: String, own: Boolean, event: CallEvent) = MatrixMessage(
        id = id,
        sender = if (own) "@me:example.org" else "@bob:example.org",
        text = "",
        own = own,
        timestampMillis = 1_760_000_000_000L,
        reactions = emptyList(),
        call = event,
    )

    @Test
    fun callRowsDescribeWhatHappenedAndOfferCallBackAndJoin() {
        val calls = mutableListOf<Boolean>()
        var joined = 0
        composeRule.setContent {
            SendTheme {
                ConversationScreen(
                    title = "Bob",
                    isDirect = true,
                    draft = "",
                    onDraft = {},
                    messages = listOf(
                        call("\$missed", own = false, CallEvent(video = false, outgoing = false)),
                        call("\$long", own = true, CallEvent(video = true, outgoing = true, answered = true, durationMillis = 151_000)),
                        call("\$declined", own = true, CallEvent(video = false, outgoing = true, declined = true)),
                        call("\$live", own = false, CallEvent(video = false, outgoing = false, ongoing = true)),
                    ),
                    onBack = {},
                    onSend = {},
                    onOlder = {},
                    onReact = { _, _ -> },
                    onCall = { calls += it },
                    onSendAttachment = {},
                    onSendVoice = {},
                    loadMedia = { _, _ -> error("No media in this fixture.") },
                    uploading = false,
                    error = "",
                    decryptionActionLabel = null,
                    onDecryptionAction = {},
                    onJoinCall = { joined++ },
                )
            }
        }
        composeRule.onNodeWithText("Missed voice call").assertIsDisplayed()
        composeRule.onNodeWithText("Video call · 2:31").assertIsDisplayed()
        composeRule.onNodeWithText("Call declined").assertIsDisplayed()
        composeRule.onNodeWithText("Voice call in progress").assertIsDisplayed()
        composeRule.onNodeWithText("Call back").performClick()
        composeRule.onNodeWithText("Join").performClick()
        composeRule.onNodeWithContentDescription("Video call").performClick()
        composeRule.onNodeWithContentDescription("Voice call").performClick()
        composeRule.runOnIdle {
            assertEquals(listOf(false, true, false), calls)
            assertEquals(1, joined)
        }
    }

    @Test
    fun runningCallShowsAListIcon() {
        composeRule.setContent {
            SendTheme {
                androidx.compose.foundation.layout.Column {
                    HomeScreen(
                        rooms = listOf(
                            MatrixRoom("!team:example.org", "Team", encrypted = true, direct = false, callActive = true, callParticipants = listOf("@a:example.org")),
                        ),
                        onOpen = {},
                        error = "",
                        notice = "",
                        creatingDirectRoom = false,
                        onCreateDirectRoom = {},
                        onCreateGroup = { _, _ -> },
                        onAccount = {},
                        onVerifyDevice = null,
                        mutedRoomIds = emptySet(),
                    )
                }
            }
        }
        composeRule.onNodeWithContentDescription("Call in progress").assertIsDisplayed()
    }

    @Test
    fun runningCallBannerJoins() {
        var joined = 0
        composeRule.setContent {
            SendTheme { RoomCallBannerRow(RoomCallBanner(participantCount = 3, video = false), onJoin = { joined++ }) }
        }
        composeRule.onNodeWithText("3 people").assertIsDisplayed()
        composeRule.onNodeWithText("Join").performClick()
        composeRule.runOnIdle { assertEquals(1, joined) }
    }

    @Test
    fun incomingCallScreenAnswersAndDeclines() {
        var answered = 0
        var declined = 0
        composeRule.setContent {
            SendTheme { IncomingCallScreen("Bob", roomName = null, video = false, onAnswer = { answered++ }, onDecline = { declined++ }) }
        }
        composeRule.onNodeWithText("Incoming voice call").assertIsDisplayed()
        composeRule.auditShot("incoming-call")
        composeRule.onNodeWithText("Bob").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Decline").performClick()
        composeRule.onNodeWithContentDescription("Answer").performClick()
        composeRule.runOnIdle {
            assertEquals(1, answered)
            assertEquals(1, declined)
        }
    }

    @Test
    fun callOutcomeComesFromMemberships() {
        val me = "@me:example.org"
        val bob = "@bob:example.org"
        fun change(at: Long, user: String, joined: Boolean) = CallMemberChange(at, "_${user}_DEV_m.call", user, joined)
        // Bob joins and rings at 10; I join at 20 and leave at 80; Bob leaves at 90: answered, 70 s.
        val answered = listOf(change(5, bob, true), change(20, me, true), change(80, me, false), change(90, bob, false))
        assertEquals(true to 70L, callOutcome(10, null, bob, me, answered))
        // Nobody joins my ring before the next call: not answered.
        assertEquals(false to null, callOutcome(10, 50, me, me, listOf(change(5, me, true), change(60, bob, true))))
        assertEquals("Missed voice call", CallEvent(video = false, outgoing = false).label)
        assertEquals("Voice call · No answer", CallEvent(video = false, outgoing = true).label)
        assertEquals("1:02:31", callDuration(3_751_000))
        val member = callMemberChange(
            """{"type":"org.matrix.msc3401.call.member","state_key":"_@b:x_D_m.call","sender":"@b:x","origin_server_ts":7,"content":{}}""",
        )
        assertEquals(CallMemberChange(7, "_@b:x_D_m.call", "@b:x", joined = false), member)
        assertNull(callMemberChange("""{"type":"m.room.message","content":{}}"""))
        val json = JSONObject(rtcTransportsJson(listOf(RtcTransport.LiveKit("https://jwt.example.org"))))
        assertEquals("https://jwt.example.org", json.getJSONArray("rtc_transports").getJSONObject(0).getString("livekit_service_url"))
    }
}
