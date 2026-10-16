package dev.send.matrix

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import java.net.URI
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import java.io.File
import java.security.MessageDigest
import java.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import dev.send.matrix.notifications.MessageNotifier
import dev.send.matrix.notifications.NotificationRouter
import dev.send.matrix.notifications.PushRegistration
import org.matrix.rustcomponents.sdk.HttpPusherData
import org.matrix.rustcomponents.sdk.NotificationClient
import org.matrix.rustcomponents.sdk.NotificationItem
import org.matrix.rustcomponents.sdk.NotificationProcessSetup
import org.matrix.rustcomponents.sdk.NotificationSettings
import org.matrix.rustcomponents.sdk.NotificationSettingsDelegate
import org.matrix.rustcomponents.sdk.NotificationStatus
import org.matrix.rustcomponents.sdk.PushFormat
import org.matrix.rustcomponents.sdk.PusherIdentifiers
import org.matrix.rustcomponents.sdk.PusherKind
import org.matrix.rustcomponents.sdk.ReceiptThread
import org.matrix.rustcomponents.sdk.RoomNotificationMode
import org.matrix.rustcomponents.sdk.OtherState
import org.matrix.rustcomponents.sdk.RtcCallIntent
import org.matrix.rustcomponents.sdk.RtcCallIntentConsensus
import org.matrix.rustcomponents.sdk.RtcTransport
import org.matrix.rustcomponents.sdk.PowerLevels
import org.matrix.rustcomponents.sdk.RoomSendQueueUpdate
import org.matrix.rustcomponents.sdk.SendQueueListener
import org.matrix.rustcomponents.sdk.SyncNotificationListener
import org.matrix.rustcomponents.sdk.BackupState
import org.matrix.rustcomponents.sdk.BackupStateListener
import org.matrix.rustcomponents.sdk.EnableRecoveryProgress
import org.matrix.rustcomponents.sdk.EnableRecoveryProgressListener
import org.matrix.rustcomponents.sdk.EncryptedMessage
import org.matrix.rustcomponents.sdk.Encryption
import org.matrix.rustcomponents.sdk.RecoveryState
import org.matrix.rustcomponents.sdk.RecoveryStateListener
import org.matrix.rustcomponents.sdk.SessionVerificationController
import org.matrix.rustcomponents.sdk.SessionVerificationControllerDelegate
import org.matrix.rustcomponents.sdk.SessionVerificationData
import org.matrix.rustcomponents.sdk.SessionVerificationRequestDetails
import org.matrix.rustcomponents.sdk.TaskHandle
import org.matrix.rustcomponents.sdk.VerificationState
import org.matrix.rustcomponents.sdk.VerificationStateListener
import uniffi.matrix_sdk_crypto.UtdCause
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.ClientBuilder
import org.matrix.rustcomponents.sdk.ClientException
import org.matrix.rustcomponents.sdk.ClientSessionDelegate
import org.matrix.rustcomponents.sdk.Session
import org.matrix.rustcomponents.sdk.SlidingSyncVersion
import org.matrix.rustcomponents.sdk.SlidingSyncVersionBuilder
import org.matrix.rustcomponents.sdk.MessageType
import org.matrix.rustcomponents.sdk.IgnoredUsersListener
import org.matrix.rustcomponents.sdk.Membership
import org.matrix.rustcomponents.sdk.MessageLikeEventContent
import org.matrix.rustcomponents.sdk.TimelineEventContent
import org.matrix.rustcomponents.sdk.QrCodeData
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.SqliteStoreBuilder
import org.matrix.rustcomponents.sdk.SsoHandler
import org.matrix.rustcomponents.sdk.TextMessageContent
import org.matrix.rustcomponents.sdk.Timeline
import org.matrix.rustcomponents.sdk.CreateRoomParameters
import org.matrix.rustcomponents.sdk.RoomPreset
import org.matrix.rustcomponents.sdk.RoomVisibility
import org.matrix.rustcomponents.sdk.AudioInfo
import org.matrix.rustcomponents.sdk.EventOrTransactionId
import org.matrix.rustcomponents.sdk.EventSendState
import org.matrix.rustcomponents.sdk.FileInfo
import org.matrix.rustcomponents.sdk.ImageInfo
import org.matrix.rustcomponents.sdk.LatestEventValue
import org.matrix.rustcomponents.sdk.MediaSource
import org.matrix.rustcomponents.sdk.AccountManagementAction
import org.matrix.rustcomponents.sdk.MembershipState
import org.matrix.rustcomponents.sdk.ProfileListener
import org.matrix.rustcomponents.sdk.UserProfile
import uniffi.matrix_sdk.RoomMemberRole
import uniffi.ruma_events.StateEventType
import org.matrix.rustcomponents.sdk.MsgLikeKind
import org.matrix.rustcomponents.sdk.EmbeddedEventDetails
import org.matrix.rustcomponents.sdk.EditedContent
import org.matrix.rustcomponents.sdk.ProfileDetails
import org.matrix.rustcomponents.sdk.contentWithoutRelationFromMessage
import org.matrix.rustcomponents.sdk.ReceiptType
import org.matrix.rustcomponents.sdk.ThumbnailInfo
import org.matrix.rustcomponents.sdk.TimelineItemContent
import org.matrix.rustcomponents.sdk.UploadParameters
import org.matrix.rustcomponents.sdk.UploadSource
import org.matrix.rustcomponents.sdk.VideoInfo

data class MatrixRoom(
    val id: String,
    val name: String,
    val encrypted: Boolean,
    val direct: Boolean,
    /** Human-readable summary of the room's latest event, or null when there is nothing to show. */
    val lastMessage: String? = null,
    val lastTimestampMillis: Long? = null,
    val unreadCount: Int = 0,
    /** A MatrixRTC call is running in the room (`RoomInfo.hasRoomCall`). */
    val callActive: Boolean = false,
    /** Who is in that call (`activeRoomCallParticipants`), this account included when it joined. */
    val callParticipants: List<String> = emptyList(),
    /** The call's members agree on video (`activeRoomCallConsensusIntent`). */
    val callVideo: Boolean = false,
    /** Pinned to the top of the list: the room's `m.favourite` tag. */
    val pinned: Boolean = false,
    /** Archived out of the main list: the room's `m.lowpriority` tag. */
    val archived: Boolean = false,
    /** Marked unread by the user (`m.marked_unread`), independent of [unreadCount]. */
    val markedUnread: Boolean = false,
    /** For a DM, the other person's Matrix ID (the room's single hero); null for groups. */
    val dmUserId: String? = null,
    /** Room avatar (`m.room.avatar`), or for a DM without one the other person's avatar; `mxc://` URL. */
    val avatarUrl: String? = null,
)

/** A starred message as listed on the conversation's Starred screen. */
data class StarredMessage(
    val eventId: String,
    val sender: String,
    val own: Boolean,
    /** Text or media summary ("Photo", …); null when the event can't be loaded or decrypted here. */
    val text: String?,
    val timestampMillis: Long?,
)

/** A tag or unread change made here, shown on the room until sync reports it (null fields are untouched). */
private data class PendingRoomFlags(
    val pinned: Boolean? = null,
    val archived: Boolean? = null,
    /** True: marked unread; false: read (flag cleared, unread count gone). */
    val unread: Boolean? = null,
    val sinceMillis: Long = 0L,
) {
    fun applyTo(room: MatrixRoom): MatrixRoom = room.copy(
        pinned = pinned ?: room.pinned,
        archived = archived ?: room.archived,
        markedUnread = unread ?: room.markedUnread,
        unreadCount = if (unread == false) 0 else room.unreadCount,
    )
}

enum class AttachmentKind { Image, Video, Audio, Voice, File }

/** Media carried by an m.image / m.video / m.audio / m.file event. Sources are kept as SDK JSON so the model stays a plain value. */
data class MatrixAttachment(
    val kind: AttachmentKind,
    val filename: String,
    val mimetype: String?,
    val sizeBytes: Long?,
    val durationMillis: Long?,
    val width: Int?,
    val height: Int?,
    val sourceJson: String,
    val thumbnailSourceJson: String?,
    /** Voice waveform normalised to 0..1 (empty when the sender did not include one). */
    val waveform: List<Float> = emptyList(),
)

enum class DeliveryStatus { Sending, Sent, Failed }

/** Why an encrypted message can't be shown yet, mapped from the SDK's unable-to-decrypt cause. */
enum class DecryptionFailure {
    /** The room key hasn't reached this device (or the SDK doesn't know why). */
    MissingKey,
    /** Sent before this account joined the conversation. */
    SentBeforeJoined,
    /** Older than this device, and this device isn't verified. */
    UnverifiedDevice,
    /** Older than this device, and key backup isn't enabled here. */
    BackupDisabled,
    /** The sender only shares keys with verified devices. */
    WithheldForUnverified,
    /** The sender deliberately withheld the key. */
    WithheldBySender,
    /** The sender's verified identity changed. */
    SenderIdentityChanged,
    /** Sent from a device its owner never verified. */
    InsecureSenderDevice,
}

/** A homeserver URL preview (OpenGraph) of a link in a message; [image] is the downloaded og:image, if any. */
data class LinkPreview(val url: String, val title: String?, val description: String?, val siteName: String?, val image: File?)

/** The message a reply quotes (`m.in_reply_to`), as far as the SDK has loaded it. */
data class ReplyQuote(
    val eventId: String,
    /** Display name (or Matrix ID) of the quoted sender; null while the SDK is still fetching the event. */
    val sender: String?,
    val own: Boolean,
    /** Text or media summary ("Photo", …) of the quoted message; null while it loads or when it is unavailable. */
    val text: String?,
)

data class MatrixMessage(
    val id: String,
    val sender: String,
    val text: String,
    val own: Boolean,
    val timestampMillis: Long,
    val reactions: List<String>,
    val attachment: MatrixAttachment? = null,
    /** Local echoes are [DeliveryStatus.Sending] until the homeserver acknowledges them; their [id] is then a transaction id. */
    val status: DeliveryStatus = DeliveryStatus.Sent,
    /** Set when the event is still encrypted for this device; [text] is empty then. */
    val decryptionFailure: DecryptionFailure? = null,
    /** Set for a MatrixRTC call notification, which renders as a call row rather than a bubble. */
    val call: CallEvent? = null,
    /** The sender's display name in this room, when the SDK has their profile. */
    val senderName: String? = null,
    /** Set when this message replies to another one. */
    val replyTo: ReplyQuote? = null,
    /** The text was replaced by an edit (`m.replace`). */
    val edited: Boolean = false,
    /** Redacted ("deleted for everyone"); [text] and [attachment] are empty then. */
    val deleted: Boolean = false,
    /** The SDK allows this account to edit the message (own text messages). */
    val editable: Boolean = false,
    /** Other people's read receipts that sit on this event: user ID → receipt time in ms (0 when unknown). */
    val readBy: Map<String, Long> = emptyMap(),
    /**
     * Stable timeline identity (the SDK's unique item ID): unlike [id] it survives the local echo becoming the
     * remote event, so list rows keep their state and don't replay entry animations.
     */
    val key: String = id,
)

/** The signed-in Matrix account and this device's session. */
data class AccountInfo(val userId: String, val deviceId: String)

/** This account's profile as others see it. */
data class OwnProfile(val displayName: String?, val avatarUrl: String?)

/** A conversation's notification level (Details → Notifications): the room's push rule. */
enum class NotificationLevel(val label: String) {
    All("All messages"),
    Mentions("Mentions only"),
    Off("Off"),
}

internal fun RoomNotificationMode.toLevel(): NotificationLevel = when (this) {
    RoomNotificationMode.ALL_MESSAGES -> NotificationLevel.All
    RoomNotificationMode.MENTIONS_AND_KEYWORDS_ONLY -> NotificationLevel.Mentions
    RoomNotificationMode.MUTE -> NotificationLevel.Off
}

internal fun NotificationLevel.toMode(): RoomNotificationMode = when (this) {
    NotificationLevel.All -> RoomNotificationMode.ALL_MESSAGES
    NotificationLevel.Mentions -> RoomNotificationMode.MENTIONS_AND_KEYWORDS_ONLY
    NotificationLevel.Off -> RoomNotificationMode.MUTE
}

enum class PersonRole { Admin, Moderator, Member }

/** Someone in a conversation: joined, or invited and not yet joined. */
data class RoomPerson(
    val userId: String,
    val displayName: String?,
    val avatarUrl: String?,
    val invited: Boolean = false,
    val role: PersonRole = PersonRole.Member,
    val isMe: Boolean = false,
) {
    val name: String get() = displayName ?: userId
}

/** Cross-signing state of the other person in a DM, as this device's crypto store sees it. */
enum class IdentityState {
    /** Verified by this account. */
    Verified,
    /** Has a cross-signing identity this account hasn't verified. */
    NotVerified,
    /** Was verified, and their identity changed since. */
    Changed,
    /** No cross-signing identity known. */
    Unknown,
}

/** Conversation details: what the page shows and which edits the room's power levels allow this account. */
data class RoomDetails(
    val roomId: String,
    val name: String,
    val topic: String?,
    val avatarUrl: String?,
    val direct: Boolean,
    val encrypted: Boolean,
    val people: List<RoomPerson>,
    val notifications: NotificationLevel,
    val canInvite: Boolean,
    val canRename: Boolean,
    val canSetTopic: Boolean,
    val canSetAvatar: Boolean,
    val dmUserId: String?,
    val dmIdentity: IdentityState?,
)

/** One of this account's sessions (devices) as the homeserver lists them. */
data class SessionDevice(
    val id: String,
    val name: String?,
    val lastSeenMillis: Long?,
    val lastSeenIp: String?,
    val current: Boolean,
)

/** Where signing another session out stands. */
sealed interface DeviceSignOut {
    data object Done : DeviceSignOut
    /** Confirm with the account password; [wrongPassword] when the last one was rejected. */
    data class Password(val session: String, val wrongPassword: Boolean) : DeviceSignOut
    /** Confirm in a browser at [url], then try again with [session] (null: the account page finishes it). */
    data class Browser(val url: String, val session: String?) : DeviceSignOut
}

data class VerificationEmoji(val symbol: String, val description: String)

/** Progress of the SAS (emoji) device verification driven by the SDK's SessionVerificationController. */
sealed interface VerificationFlow {
    data object Idle : VerificationFlow
    /** This device asked the user's other sessions to verify it. */
    data object Requesting : VerificationFlow
    /** Another session asked to verify; waiting for the user to accept or decline. */
    data class Incoming(val userId: String, val deviceId: String, val deviceName: String?) : VerificationFlow
    /** Request accepted here; waiting for the other session to start the emoji comparison. */
    data object Accepted : VerificationFlow
    /** SAS started; waiting for the key exchange that produces the emoji. */
    data object Starting : VerificationFlow
    data class Emojis(val emojis: List<VerificationEmoji>) : VerificationFlow
    data class Decimals(val values: List<Int>) : VerificationFlow
    /** Confirmed here; waiting for the other session to confirm too. */
    data object Confirming : VerificationFlow
    data object Verified : VerificationFlow
    data class Cancelled(val reason: String?) : VerificationFlow
    data class Failed(val reason: String?) : VerificationFlow
}

private val VerificationFlow.inProgress: Boolean
    get() = when (this) {
        VerificationFlow.Idle, VerificationFlow.Verified, is VerificationFlow.Cancelled, is VerificationFlow.Failed -> false
        else -> true
    }

/** A finished voice recording ready for upload. */
data class VoiceRecording(val file: File, val durationMillis: Long, val waveform: List<Float>)

class MatrixRepository(context: Context) {
    private val appContext = context.applicationContext
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    @Volatile private var callSession: NativeMatrixCallSession? = null
    @Volatile private var client: Client? = null
    /**
     * Built once per session and shared: [startSync] runs it, and the notification client (push / background
     * fetches) uses it for its single-process setup even when it isn't running. [syncStarted] tells the two apart.
     */
    @Volatile private var syncService: org.matrix.rustcomponents.sdk.SyncService? = null
    @Volatile private var syncStarted = false
    private val syncLock = Mutex()
    /** Serialises [restore] so a notification action, a worker and the activity never build two clients on one store. */
    private val restoreLock = Mutex()
    @Volatile private var notificationClient: NotificationClient? = null
    private val notificationClientLock = Mutex()
    @Volatile private var notificationSettings: NotificationSettings? = null
    private val notificationSettingsLock = Mutex()
    private val _mutedRoomIds = MutableStateFlow<Set<String>>(emptySet())
    /** Rooms whose user-defined push rule is MUTE (`NotificationSettings`); kept current by the settings delegate. */
    val mutedRoomIds: StateFlow<Set<String>> = _mutedRoomIds.asStateFlow()
    @Volatile private var roomListService: org.matrix.rustcomponents.sdk.RoomListService? = null
    private var roomList: org.matrix.rustcomponents.sdk.RoomList? = null
    private var roomEntriesResult: org.matrix.rustcomponents.sdk.RoomListEntriesWithDynamicAdaptersResult? = null
    private var roomEntriesController: org.matrix.rustcomponents.sdk.RoomListDynamicEntriesController? = null
    private var roomEntriesHandle: org.matrix.rustcomponents.sdk.TaskHandle? = null
    private var roomListStateHandle: org.matrix.rustcomponents.sdk.TaskHandle? = null
    private var timelineHandle: org.matrix.rustcomponents.sdk.TaskHandle? = null
    private val timelineItems = mutableListOf<org.matrix.rustcomponents.sdk.TimelineItem>()
    private val _joinedRooms = MutableStateFlow<List<MatrixRoom>>(emptyList())
    val joinedRooms: StateFlow<List<MatrixRoom>> = _joinedRooms.asStateFlow()
    private val locallyCreatedRooms = mutableMapOf<String, MatrixRoom>()
    /**
     * Index-for-index mirror of the SDK room list. Diff batches apply one at a time (under [roomEntriesLock]);
     * [roomListGeneration] changes when a session's list is torn down so late batches are dropped.
     */
    private val roomEntries = mutableListOf<MatrixRoom>()
    private val roomEntriesLock = Mutex()
    private var roomListGeneration = 0
    /** Tag and unread changes sent from here that sync hasn't echoed back yet (guarded by the [roomEntries] monitor). */
    private val pendingRoomFlags = HashMap<String, PendingRoomFlags>()
    /** Conversations being left and forgotten, hidden until the SDK list drops them (guarded by [roomEntries]). */
    private val pendingRemovals = HashSet<String>()
    @Volatile private var ignoredUsersHandle: TaskHandle? = null
    private val _ignoredUsers = MutableStateFlow<Set<String>>(emptySet())
    /** Users this account blocked (`m.ignored_user_list`); their DMs leave the conversation list. */
    val ignoredUsers: StateFlow<Set<String>> = _ignoredUsers.asStateFlow()
    private val _starredEvents = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    /** Starred messages per room, oldest star first, from the account data event [STARRED_EVENT_TYPE]. */
    val starredEvents: StateFlow<Map<String, List<String>>> = _starredEvents.asStateFlow()
    @Volatile private var ownProfileHandle: TaskHandle? = null
    private val _ownProfile = MutableStateFlow<OwnProfile?>(null)
    /** This account's display name and avatar (Settings → profile), kept current from the homeserver. */
    val ownProfile: StateFlow<OwnProfile?> = _ownProfile.asStateFlow()
    private val starredLock = Mutex()
    /** The starred map this device last wrote and when, until sync brings it back into the store. */
    private var starredWrite: Pair<Map<String, List<String>>, Long>? = null
    private val _roomListServiceState = MutableStateFlow(org.matrix.rustcomponents.sdk.RoomListServiceState.INITIAL)
    val roomListServiceState: StateFlow<org.matrix.rustcomponents.sdk.RoomListServiceState> = _roomListServiceState.asStateFlow()
    private val _messages = MutableStateFlow<List<MatrixMessage>>(emptyList())
    val messages: StateFlow<List<MatrixMessage>> = _messages.asStateFlow()
    /** The open conversation's timeline (the one [messages] mirrors). */
    @Volatile private var observedTimeline: Timeline? = null
    /** Reply events whose quoted event was asked for with `fetchDetailsForEvent`, so each is fetched once. */
    private val replyDetailsRequested = java.util.Collections.synchronizedSet(HashSet<String>())
    @Volatile private var typingHandle: TaskHandle? = null
    private val _typingUsers = MutableStateFlow<List<String>>(emptyList())
    /** Other people typing in the open conversation (display names, Matrix ID when unknown). */
    val typingUsers: StateFlow<List<String>> = _typingUsers.asStateFlow()

    // E2EE account state: SDK listeners feed these while a session is active; they reset on sign-out.
    @Volatile private var encryption: Encryption? = null
    @Volatile private var verificationController: SessionVerificationController? = null
    private val verificationControllerLock = Mutex()
    private val securityHandles = mutableListOf<TaskHandle>()
    @Volatile private var securityJob: Job? = null
    /** True when this device asked to be verified, so it is the side that starts the SAS exchange. */
    @Volatile private var outgoingVerification = false
    private val _account = MutableStateFlow<AccountInfo?>(null)
    val account: StateFlow<AccountInfo?> = _account.asStateFlow()
    private val _recoveryState = MutableStateFlow(RecoveryState.UNKNOWN)
    val recoveryState: StateFlow<RecoveryState> = _recoveryState.asStateFlow()
    private val _backupState = MutableStateFlow(BackupState.UNKNOWN)
    val backupState: StateFlow<BackupState> = _backupState.asStateFlow()
    private val _verificationState = MutableStateFlow(VerificationState.UNKNOWN)
    val verificationState: StateFlow<VerificationState> = _verificationState.asStateFlow()
    private val _hasDevicesToVerifyAgainst = MutableStateFlow(false)
    /** Whether another cross-signed session of this account exists that could verify this one. */
    val hasDevicesToVerifyAgainst: StateFlow<Boolean> = _hasDevicesToVerifyAgainst.asStateFlow()
    private val _verificationFlow = MutableStateFlow<VerificationFlow>(VerificationFlow.Idle)
    val verificationFlow: StateFlow<VerificationFlow> = _verificationFlow.asStateFlow()

    // Called on SDK threads; it only publishes state and hands follow-up SDK calls to the repository scope.
    private val verificationDelegate = object : SessionVerificationControllerDelegate {
        override fun didReceiveVerificationRequest(details: SessionVerificationRequestDetails) {
            if (_verificationFlow.value.inProgress) return
            repositoryScope.launch {
                try {
                    val controller = verificationController ?: return@launch
                    controller.acknowledgeVerificationRequest(details.senderProfile.userId, details.flowId)
                    outgoingVerification = false
                    _verificationFlow.value = VerificationFlow.Incoming(
                        userId = details.senderProfile.userId,
                        deviceId = details.deviceId,
                        deviceName = details.deviceDisplayName?.takeIf { it.isNotBlank() },
                    )
                } catch (error: Exception) {
                    android.util.Log.w(TAG, "Could not acknowledge verification request", error)
                }
            }
        }

        override fun didAcceptVerificationRequest() {
            if (!outgoingVerification) {
                _verificationFlow.value = VerificationFlow.Accepted
                return
            }
            _verificationFlow.value = VerificationFlow.Starting
            repositoryScope.launch {
                try {
                    verificationController?.startSasVerification()
                } catch (error: Exception) {
                    _verificationFlow.value = VerificationFlow.Failed(error.message)
                }
            }
        }

        override fun didStartSasVerification() {
            val current = _verificationFlow.value
            if (current !is VerificationFlow.Emojis && current !is VerificationFlow.Decimals) {
                _verificationFlow.value = VerificationFlow.Starting
            }
        }

        override fun didReceiveVerificationData(data: SessionVerificationData) {
            _verificationFlow.value = when (data) {
                is SessionVerificationData.Emojis ->
                    VerificationFlow.Emojis(data.emojis.map { VerificationEmoji(it.symbol(), it.description()) })
                is SessionVerificationData.Decimals -> VerificationFlow.Decimals(data.values.map { it.toInt() })
            }
        }

        override fun didFail() {
            _verificationFlow.value = VerificationFlow.Failed(null)
        }

        override fun didCancel() {
            // A cancel this device initiated already returned the flow to Idle.
            if (_verificationFlow.value == VerificationFlow.Idle) return
            val controller = verificationController
            val info = controller?.sasCancelInfo() ?: controller?.requestCancelInfo()
            _verificationFlow.value = VerificationFlow.Cancelled(info?.reason)
        }

        override fun didFinish() {
            _verificationFlow.value = VerificationFlow.Verified
            refreshSecurityStatus()
        }
    }

    // The SDK calls this on token refresh (save) and when it needs the stored tokens again (retrieve).
    private val sessionDelegate = object : ClientSessionDelegate {
        override fun retrieveSessionFromKeychain(userId: String): Session =
            savedSession()?.takeIf { it.userId == userId }
                ?: throw ClientException.Generic("No saved session for $userId", null)

        override fun saveSessionInKeychain(session: Session) {
            try {
                persistSession(session)
            } catch (error: Exception) {
                android.util.Log.e(TAG, "Refreshed session could not be saved", error)
            }
        }
    }

    /**
     * Reopens the session saved at sign-in (or on the last token refresh) on the existing store, as on a
     * cold start. Returns false when there is nothing to restore.
     */
    suspend fun restore(): Boolean = withContext(Dispatchers.IO) {
        restoreLock.withLock {
            if (client != null) return@withLock true
            if (hasUnencryptedLegacyStore()) {
                // The key can't open a store written without it: start over with a new sign-in.
                android.util.Log.w(TAG, "Discarding an unencrypted store from an older build")
                wipeLocalSession()
                return@withLock false
            }
            val session = savedSession() ?: return@withLock false
            val sdk = createClient(session.homeserverUrl)
            try {
                sdk.restoreSession(session)
                client = sdk
                attachNotificationHandler(sdk)
                true
            } catch (error: Exception) {
                if (client === sdk) client = null
                sdk.close()
                throw error
            }
        }
    }

    /**
     * Routes notifications computed from sync responses (push rules evaluated on the decrypted event when the
     * key is already here) to [NotificationRouter], which posts them while the app is in the background.
     */
    private suspend fun attachNotificationHandler(sdk: Client) {
        sdk.registerNotificationHandler(object : SyncNotificationListener {
            override fun onNotification(notification: NotificationItem, roomId: String) {
                if (client === sdk) NotificationRouter.onSyncNotification(appContext, notification, roomId)
            }
        })
    }


    suspend fun qrLogin(homeserver: String, qrPayload: String) = withContext(Dispatchers.IO) {
        discardUnrestorableStore()
        val sdk = createClient(resolveHomeserverUrl(homeserver))
        try {
            check(sdk.isLoginWithQrCodeSupported()) { "This homeserver does not support Matrix QR sign-in." }
            val oauthConfiguration = org.matrix.rustcomponents.sdk.OAuthConfiguration(
                clientName = "Send Android",
                redirectUri = "dev.send.matrix://sso/callback",
                clientUri = "https://github.com/hanthor/send-android",
                logoUri = null,
                tosUri = null,
                policyUri = null,
                staticRegistrations = emptyMap(),
            )
            val handler = sdk.newLoginWithQrCodeHandler(oauthConfiguration)
            // scan is a suspending SDK operation; it returns only after the QR login
            // exchange completes (or throws), so session restoration must follow it.
            handler.scan(
                QrCodeData.fromBytes(qrPayload.toByteArray(Charsets.UTF_8)),
                object : org.matrix.rustcomponents.sdk.QrLoginProgressListener {
                    override fun onUpdate(state: org.matrix.rustcomponents.sdk.QrLoginProgress) = Unit
                },
            )
            val session = sdk.session()
            sdk.restoreSession(session)
            saveAuthenticatedClient(sdk)
        } catch (error: Exception) {
            sdk.close()
            throw error
        }
    }

    @Volatile private var ssoClient: Client? = null
    @Volatile private var ssoHandler: SsoHandler? = null

    suspend fun beginSsoLogin(homeserver: String): String = withContext(Dispatchers.IO) {
        cancelSsoLogin()
        discardUnrestorableStore()
        val sdk = createClient(resolveHomeserverUrl(homeserver))
        try {
            val handler = sdk.startSsoLogin("dev.send.matrix://sso/callback", null)
            ssoClient = sdk
            ssoHandler = handler
            handler.url()
        } catch (error: Exception) {
            sdk.close()
            throw error
        }
    }

    suspend fun finishSsoLogin(callback: String) = withContext(Dispatchers.IO) {
        val sdk = ssoClient ?: error("This SSO sign-in has expired. Start again.")
        val handler = ssoHandler ?: error("This SSO sign-in has expired. Start again.")
        try {
            handler.finish(callback)
            val session = sdk.session()
            sdk.restoreSession(session)
            saveAuthenticatedClient(sdk)
            ssoClient = null
            ssoHandler = null
        } catch (error: Exception) {
            sdk.close()
            ssoClient = null
            ssoHandler = null
            throw error
        }
    }

    fun cancelSsoLogin() {
        ssoClient?.close()
        ssoClient = null
        ssoHandler = null
    }
    suspend fun passwordLogin(homeserver: String, user: String, password: String) = withContext(Dispatchers.IO) {
        discardUnrestorableStore()
        val sdk = createClient(resolveHomeserverUrl(effectiveServerInput(homeserver, user)))
        try {
            sdk.login(user, password, "Send Android", null)
            saveAuthenticatedClient(sdk)
        } catch (error: Exception) {
            sdk.close()
            throw error
        }
    }

    private suspend fun saveAuthenticatedClient(sdk: Client) {
        attachNotificationHandler(sdk)
        persistSession(sdk.session())
        // Notifications only cover what arrives after this sign-in, not the history a new device syncs.
        NotificationRouter.onSignedIn(appContext)
        replaceAuthenticatedClient(sdk)
    }

    /** A new sign-in is a new device: a store left without saved credentials can't be reused, so remove it first. */
    private fun discardUnrestorableStore() {
        if (client == null && (savedSession() == null || hasUnencryptedLegacyStore()) && appContext.filesDir.resolve(STORE_DIR).exists()) {
            wipeLocalSession()
        }
    }

    /** Builds before the store key applied wrote unencrypted SQLite stores to `cache` and `crypto-store`. */
    private fun hasUnencryptedLegacyStore(): Boolean =
        appContext.filesDir.resolve(STORE_DIR).resolve(LEGACY_UNENCRYPTED_STORE_DIR).exists()

    private fun replaceAuthenticatedClient(sdk: Client) {
        closeSessionResources()
        client = sdk
    }

    private fun closeSessionResources() {
        timelineHandle?.close()
        timelineHandle = null
        observedTimeline = null
        typingHandle?.close()
        typingHandle = null
        _typingUsers.value = emptyList()
        closeSecurityResources()
        closeRoomListResources()
        notificationClient?.close()
        notificationClient = null
        notificationSettings?.let { settings ->
            settings.setDelegate(null)
            settings.close()
        }
        notificationSettings = null
        _mutedRoomIds.value = emptySet()
        ignoredUsersHandle?.close()
        ignoredUsersHandle = null
        _ignoredUsers.value = emptySet()
        ownProfileHandle?.close()
        ownProfileHandle = null
        _ownProfile.value = null
        _starredEvents.value = emptyMap()
        starredWrite = null
        syncService?.close()
        syncService = null
        syncStarted = false
        callSession?.close()
        callSession = null
        client?.close()
        client = null
        synchronized(locallyCreatedRooms) { locallyCreatedRooms.clear() }
        synchronized(roomEntries) {
            roomListGeneration++
            roomEntries.clear()
            pendingRoomFlags.clear()
            pendingRemovals.clear()
            _joinedRooms.value = emptyList()
        }
        timelineItems.clear()
        _messages.value = emptyList()
        _roomListServiceState.value = org.matrix.rustcomponents.sdk.RoomListServiceState.INITIAL
    }

    private fun closeRoomListResources() {
        roomListStateHandle?.close()
        roomListStateHandle = null
        roomEntriesHandle?.close()
        roomEntriesHandle = null
        roomEntriesController?.close()
        roomEntriesController = null
        roomEntriesResult?.close()
        roomEntriesResult = null
        roomList?.close()
        roomList = null
        roomListService?.close()
        roomListService = null
    }

    /**
     * Releases every SDK object (stopping sync first) without signing out, as process death would;
     * [restore] reopens the saved session afterwards.
     */
    suspend fun close() = withContext(Dispatchers.IO) {
        try {
            syncService?.stop()
        } catch (error: Exception) {
            android.util.Log.w(TAG, "Sync did not stop cleanly", error)
        }
        closeSessionResources()
        cancelSsoLogin()
    }

    /**
     * Runs the sync service (room list, encryption, security state). Once started, calling it again restarts a
     * sync that stopped on an error or while the process was frozen in the background; the SDK ignores the call
     * while it is running.
     */
    suspend fun startSync() = withContext(Dispatchers.IO) {
        syncLock.withLock {
            val sdk = requireClient()
            val running = syncService
            if (syncStarted && running != null) {
                running.start()
                return@withLock
            }
            val service = ensureSyncService(sdk)
            try {
                startRoomList(service)
                service.start()
                syncStarted = true
                startSecurity(sdk)
            } catch (error: Exception) {
                closeSecurityResources()
                closeRoomListResources()
                throw error
            }
        }
        refreshMutedRooms()
        startListState()
    }

    /** Follows the ignored-user list and loads starred messages for the session that just started syncing. */
    private fun startListState() {
        val sdk = client ?: return
        if (ignoredUsersHandle == null) {
            ignoredUsersHandle = sdk.subscribeToIgnoredUsers(object : IgnoredUsersListener {
                override fun call(ignoredUserIds: List<String>) {
                    if (client === sdk) _ignoredUsers.value = ignoredUserIds.toSet()
                }
            })
        }
        if (ownProfileHandle == null) {
            ownProfileHandle = sdk.subscribeToOwnProfile(object : ProfileListener {
                override fun onUpdate(profile: UserProfile) {
                    if (client === sdk) _ownProfile.value = OwnProfile(profile.displayName, profile.avatarUrl)
                }
            })
        }
        repositoryScope.launch {
            try {
                refreshOwnProfile()
            } catch (error: Exception) {
                android.util.Log.w(TAG, "Own profile could not be read", error)
            }
            try {
                refreshIgnoredUsers()
            } catch (error: Exception) {
                android.util.Log.w(TAG, "Blocked users could not be read", error)
            }
            try {
                refreshStarred()
            } catch (error: Exception) {
                android.util.Log.w(TAG, "Starred messages could not be read", error)
            }
        }
    }

    /** Whether this session's sync service has been started (by the app or a background worker). */
    val isSyncStarted: Boolean get() = syncStarted

    private suspend fun ensureSyncService(sdk: Client): org.matrix.rustcomponents.sdk.SyncService =
        syncService ?: sdk.syncService().finish().also { syncService = it }

    /**
     * Notification client sharing this process's sync service (`NotificationProcessSetup.SingleProcess`): while
     * sync runs it waits for that sync's room keys; when it doesn't (cold push, background fetch), the SDK runs the
     * encryption sync itself before decrypting.
     */
    private suspend fun requireNotificationClient(): NotificationClient = notificationClientLock.withLock {
        notificationClient ?: run {
            val sdk = requireClient()
            val service = syncLock.withLock { ensureSyncService(sdk) }
            sdk.notificationClient(NotificationProcessSetup.SingleProcess(service)).also { notificationClient = it }
        }
    }

    /** Fetches and decrypts one event for a notification (push or an event sync delivered still encrypted). */
    internal suspend fun fetchNotification(roomId: String, eventId: String): NotificationItem? = withContext(Dispatchers.IO) {
        when (val status = requireNotificationClient().getNotification(roomId, eventId)) {
            is NotificationStatus.Event -> status.item
            else -> null
        }
    }

    internal fun ownUserId(): String? = client?.let { sdk -> runCatching { sdk.userId() }.getOrNull() }

    /** True when this account's read receipt in [roomId] was sent at or after [timestampMillis] (read on any device). */
    internal suspend fun hasReadSince(roomId: String, timestampMillis: Long): Boolean = withContext(Dispatchers.IO) {
        val sdk = client ?: return@withContext false
        try {
            sdk.getRoom(roomId)?.use { room ->
                val userId = sdk.userId()
                listOf(ReceiptThread.Unthreaded, ReceiptThread.Main).any { thread ->
                    val readAt = room.loadUserReceipt(ReceiptType.READ, thread, userId)?.receipt?.timestamp?.toLong()
                    readAt != null && readAt >= timestampMillis
                }
            } ?: false
        } catch (error: Exception) {
            android.util.Log.w(TAG, "Read receipt for $roomId unavailable", error)
            false
        }
    }

    private suspend fun requireNotificationSettings(): NotificationSettings = notificationSettingsLock.withLock {
        notificationSettings ?: requireClient().getNotificationSettings().also { settings ->
            settings.setDelegate(object : NotificationSettingsDelegate {
                // Push rules changed (here or on another device).
                override fun settingsDidChange() {
                    if (notificationSettings === settings) refreshMutedRooms()
                }
            })
            notificationSettings = settings
        }
    }

    private fun refreshMutedRooms() {
        repositoryScope.launch {
            try {
                val settings = requireNotificationSettings()
                val muted = settings.getRoomsWithUserDefinedRules(true).filterTo(mutableSetOf()) { roomId ->
                    runCatching { settings.getUserDefinedRoomNotificationMode(roomId) }.getOrNull() == RoomNotificationMode.MUTE
                }
                if (notificationSettings === settings) _mutedRoomIds.value = muted
            } catch (error: Exception) {
                android.util.Log.w(TAG, "Muted conversations could not be read", error)
            }
        }
    }

    /** Whether the user muted [roomId]; checked again before posting, as push rules from sync may lag a change. */
    internal suspend fun isRoomMuted(roomId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            requireNotificationSettings().getUserDefinedRoomNotificationMode(roomId) == RoomNotificationMode.MUTE
        } catch (error: Exception) {
            android.util.Log.w(TAG, "Notification mode for $roomId unavailable", error)
            false
        }
    }

    /**
     * Mutes a conversation with a user-defined MUTE push rule (no push, no sync notification), or removes it so
     * the account's default mode for this kind of room applies again.
     */
    suspend fun setRoomMuted(roomId: String, muted: Boolean) = withContext(Dispatchers.IO) {
        val settings = requireNotificationSettings()
        if (muted) {
            settings.setRoomNotificationMode(roomId, RoomNotificationMode.MUTE)
            MessageNotifier.cancel(appContext, roomId)
        } else {
            val room = requireClient().getRoom(roomId) ?: error("Conversation is unavailable.")
            room.use { settings.unmuteRoom(roomId, it.isEncrypted(), it.activeMembersCount() <= 2uL) }
        }
        _mutedRoomIds.value = if (muted) _mutedRoomIds.value + roomId else _mutedRoomIds.value - roomId
    }

    /**
     * Sends a notification's inline reply as an encrypted text message (restoring the session first when the
     * process was cold) and returns once the homeserver has accepted it.
     */
    suspend fun sendNotificationReply(roomId: String, text: String): Unit = withContext(Dispatchers.IO) {
        check(restore()) { "Sign in to a Matrix account first." }
        openEncryptedRoom(roomId).use { room ->
            val sent = CompletableDeferred<Unit>()
            val handle = room.subscribeToSendQueueUpdates(object : SendQueueListener {
                override fun onUpdate(update: RoomSendQueueUpdate) {
                    when (update) {
                        is RoomSendQueueUpdate.SentEvent -> sent.complete(Unit)
                        is RoomSendQueueUpdate.SendError ->
                            if (!update.isRecoverable) sent.completeExceptionally(IllegalStateException("The reply could not be sent."))
                        else -> Unit
                    }
                }
            })
            try {
                timeline(room).use { timeline ->
                    sendText(timeline, room, text)
                    withTimeout(REPLY_SEND_TIMEOUT_MS) { sent.await() }
                }
            } finally {
                handle.close()
            }
        }
    }

    /**
     * Sends a read receipt from a notification: up to [eventId] (the newest notified event) when known, otherwise
     * for the room's latest event. Restores the session first when the process was cold.
     */
    suspend fun markRoomRead(roomId: String, eventId: String?) = withContext(Dispatchers.IO) {
        check(restore()) { "Sign in to a Matrix account first." }
        requireClient().getRoom(roomId)?.use { room ->
            if (eventId != null) room.sendSingleReceipt(ReceiptType.READ, ReceiptThread.Unthreaded, eventId)
            else room.markAsRead(ReceiptType.READ)
        } ?: error("Conversation is unavailable.")
        refreshRoom(roomId)
    }

    /** Registers this device's HTTP pusher: the homeserver posts event-ID-only payloads to [gatewayUrl] for [pushkey]. */
    internal suspend fun setHttpPusher(pushkey: String, gatewayUrl: String) = withContext(Dispatchers.IO) {
        check(restore()) { "Sign in to a Matrix account first." }
        requireClient().setPusher(
            identifiers = PusherIdentifiers(pushkey, PUSHER_APP_ID),
            kind = PusherKind.Http(HttpPusherData(gatewayUrl, PushFormat.EVENT_ID_ONLY, null)),
            appDisplayName = "Send",
            deviceDisplayName = "Send on ${android.os.Build.MODEL}",
            profileTag = null,
            lang = java.util.Locale.getDefault().toLanguageTag(),
            append = false,
        )
    }

    internal suspend fun deletePusher(pushkey: String) = withContext(Dispatchers.IO) {
        requireClient().deletePusher(PusherIdentifiers(pushkey, PUSHER_APP_ID))
    }

    internal val isSignedIn: Boolean get() = client != null || savedSession() != null

    /** Subscribes to the SDK's recovery, key-backup and verification state and prepares the verification controller. */
    private fun startSecurity(sdk: Client) {
        val sdkEncryption = sdk.encryption()
        encryption = sdkEncryption
        _account.value = AccountInfo(sdk.userId(), sdk.deviceId())
        _recoveryState.value = sdkEncryption.recoveryState()
        _backupState.value = sdkEncryption.backupState()
        _verificationState.value = sdkEncryption.verificationState()
        synchronized(securityHandles) {
            securityHandles += sdkEncryption.recoveryStateListener(object : RecoveryStateListener {
                override fun onUpdate(status: RecoveryState) {
                    if (encryption === sdkEncryption) _recoveryState.value = status
                }
            })
            securityHandles += sdkEncryption.backupStateListener(object : BackupStateListener {
                override fun onUpdate(status: BackupState) {
                    if (encryption === sdkEncryption) _backupState.value = status
                }
            })
            securityHandles += sdkEncryption.verificationStateListener(object : VerificationStateListener {
                override fun onUpdate(status: VerificationState) {
                    if (encryption !== sdkEncryption) return
                    _verificationState.value = status
                    // Own-device changes drive this listener, so it is also when other sessions appear or get verified.
                    refreshSecurityStatus()
                }
            })
        }
        securityJob = repositoryScope.launch {
            // Cross-signing bootstrap (autoEnableCrossSigning) runs here after a first login.
            sdkEncryption.waitForE2eeInitializationTasks()
            updateSecurityStatus(sdkEncryption)
        }
    }

    private fun closeSecurityResources() {
        securityJob?.cancel()
        securityJob = null
        synchronized(securityHandles) {
            securityHandles.forEach { it.close() }
            securityHandles.clear()
        }
        verificationController?.let { controller ->
            controller.setDelegate(null)
            controller.close()
        }
        verificationController = null
        encryption?.close()
        encryption = null
        outgoingVerification = false
        _account.value = null
        _recoveryState.value = RecoveryState.UNKNOWN
        _backupState.value = BackupState.UNKNOWN
        _verificationState.value = VerificationState.UNKNOWN
        _hasDevicesToVerifyAgainst.value = false
        _verificationFlow.value = VerificationFlow.Idle
    }

    /** Re-checks whether other sessions can verify this one and makes sure incoming verification requests are handled. */
    fun refreshSecurityStatus() {
        val sdkEncryption = encryption ?: return
        repositoryScope.launch { updateSecurityStatus(sdkEncryption) }
    }

    private suspend fun updateSecurityStatus(sdkEncryption: Encryption) {
        try {
            val hasDevices = sdkEncryption.hasDevicesToVerifyAgainst()
            if (encryption === sdkEncryption) _hasDevicesToVerifyAgainst.value = hasDevices
        } catch (error: Exception) {
            android.util.Log.w(TAG, "Could not list sessions to verify against", error)
        }
        try {
            ensureVerificationController()
        } catch (error: Exception) {
            // The controller needs this account's cross-signing identity; it is retried on the next state change.
            android.util.Log.w(TAG, "Session verification is not available yet", error)
        }
    }

    private suspend fun ensureVerificationController(): SessionVerificationController = verificationControllerLock.withLock {
        verificationController ?: requireClient().getSessionVerificationController().also { controller ->
            controller.setDelegate(verificationDelegate)
            verificationController = controller
        }
    }

    private fun requireEncryption(): Encryption = encryption ?: error("Sign in to a Matrix account first.")

    /**
     * Creates key backup and secret storage (Encryption.enableRecovery), waiting until existing room keys are
     * uploaded, and returns the recovery key. The key is never stored by Send.
     */
    suspend fun enableRecovery(onProgress: (String) -> Unit): String = withContext(Dispatchers.IO) {
        requireEncryption().enableRecovery(
            waitForBackupsToUpload = true,
            passphrase = null,
            progressListener = object : EnableRecoveryProgressListener {
                override fun onUpdate(status: EnableRecoveryProgress) {
                    val text = when (status) {
                        is EnableRecoveryProgress.Starting -> "Starting…"
                        is EnableRecoveryProgress.CreatingBackup -> "Creating key backup…"
                        is EnableRecoveryProgress.CreatingRecoveryKey -> "Creating recovery key…"
                        is EnableRecoveryProgress.BackingUp -> "Backing up keys (${status.backedUpCount} of ${status.totalCount})…"
                        is EnableRecoveryProgress.RoomKeyUploadError -> "Some keys will finish backing up in the background."
                        is EnableRecoveryProgress.Done -> null
                    }
                    if (text != null) onProgress(text)
                }
            },
        )
    }

    /** Unlocks secret storage with the recovery key: imports cross-signing keys (verifying this device) and the backup key. */
    suspend fun recover(recoveryKey: String) = withContext(Dispatchers.IO) {
        val key = recoveryKey.trim()
        require(key.isNotEmpty()) { "Enter your recovery key." }
        requireEncryption().recover(key)
    }

    /** Waits until every room key this device holds has been uploaded to key backup. */
    suspend fun waitForKeyBackup() = withContext(Dispatchers.IO) {
        requireEncryption().waitForBackupUploadSteadyState(null)
    }

    /** Asks this account's other sessions to verify this device with emoji (SAS). */
    suspend fun requestDeviceVerification(): Unit = withContext(Dispatchers.IO) {
        try {
            val controller = ensureVerificationController()
            outgoingVerification = true
            _verificationFlow.value = VerificationFlow.Requesting
            controller.requestDeviceVerification()
        } catch (error: Exception) {
            _verificationFlow.value = VerificationFlow.Failed(error.message)
        }
    }

    suspend fun acceptVerificationRequest(): Unit = withContext(Dispatchers.IO) {
        try {
            _verificationFlow.value = VerificationFlow.Accepted
            ensureVerificationController().acceptVerificationRequest()
        } catch (error: Exception) {
            _verificationFlow.value = VerificationFlow.Failed(error.message)
        }
    }

    /** Reports whether the emoji (or numbers) shown on both devices match. */
    suspend fun confirmVerification(matches: Boolean): Unit = withContext(Dispatchers.IO) {
        try {
            val controller = ensureVerificationController()
            if (matches) {
                // Set first: the other side may finish before approveVerification returns.
                _verificationFlow.value = VerificationFlow.Confirming
                controller.approveVerification()
            } else {
                controller.declineVerification()
            }
        } catch (error: Exception) {
            _verificationFlow.value = VerificationFlow.Failed(error.message)
        }
    }

    /** Cancels (or declines) the current verification request on both devices. */
    suspend fun cancelVerification(): Unit = withContext(Dispatchers.IO) {
        _verificationFlow.value = VerificationFlow.Idle
        try {
            verificationController?.cancelVerification()
        } catch (error: Exception) {
            android.util.Log.w(TAG, "Verification could not be cancelled", error)
        }
    }

    /** Clears a finished, cancelled or failed verification from the UI. */
    fun dismissVerification() {
        if (!_verificationFlow.value.inProgress) _verificationFlow.value = VerificationFlow.Idle
    }

    /**
     * Signs out: waits briefly for pending key-backup uploads, logs the session out on the homeserver
     * (unless [serverSide] is false because the server is unreachable), stops sync, closes every SDK
     * resource and deletes the local SDK/crypto stores, media cache and session preferences.
     */
    suspend fun logout(serverSide: Boolean = true) = withContext(Dispatchers.IO) {
        val sdk = client
        if (sdk != null && serverSide) {
            // Pushers outlive the access token on some servers; remove this device's while the session still works.
            PushRegistration.removePusher(appContext, this@MatrixRepository)
            if (_backupState.value == BackupState.ENABLED) {
                try {
                    withTimeoutOrNull(BACKUP_UPLOAD_TIMEOUT_MS) { encryption?.waitForBackupUploadSteadyState(null) }
                } catch (error: Exception) {
                    android.util.Log.w(TAG, "Key backup did not settle before sign-out", error)
                }
            }
            sdk.logout()
        }
        try {
            syncService?.stop()
        } catch (error: Exception) {
            android.util.Log.w(TAG, "Sync did not stop cleanly", error)
        }
        closeSessionResources()
        wipeLocalSession()
        PushRegistration.onSignedOut(appContext)
        MessageNotifier.clearAll(appContext)
        ShareTarget.clearAll(appContext)
        NotificationRouter.onSignedOut(appContext)
    }

    private fun wipeLocalSession() {
        appContext.filesDir.resolve(STORE_DIR).deleteRecursively()
        appContext.cacheDir.resolve("media").deleteRecursively()
        // Saved session, store key and anything older builds kept here.
        check(prefs().edit().clear().commit()) { "The signed-out session could not be cleared." }
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(STORE_KEY_ALIAS)
    }

    private fun prefs() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Saves the SDK session (tokens, user, device, homeserver) sealed with the Keystore key for cold starts. */
    private fun persistSession(session: Session) {
        val json = org.json.JSONObject()
            .put("accessToken", session.accessToken)
            .put("refreshToken", session.refreshToken)
            .put("userId", session.userId)
            .put("deviceId", session.deviceId)
            .put("homeserverUrl", session.homeserverUrl)
            .put("oauthData", session.oauthData)
            .put("slidingSyncVersion", session.slidingSyncVersion.name)
        check(prefs().edit().putString(KEY_SESSION, seal(json.toString().toByteArray())).commit()) {
            "The session could not be saved."
        }
    }

    private fun savedSession(): Session? {
        val sealed = prefs().getString(KEY_SESSION, null) ?: return null
        return try {
            val json = org.json.JSONObject(String(unseal(sealed)))
            fun optional(name: String) = if (json.isNull(name)) null else json.getString(name)
            Session(
                accessToken = json.getString("accessToken"),
                refreshToken = optional("refreshToken"),
                userId = json.getString("userId"),
                deviceId = json.getString("deviceId"),
                homeserverUrl = json.getString("homeserverUrl"),
                oauthData = optional("oauthData"),
                slidingSyncVersion = SlidingSyncVersion.valueOf(json.getString("slidingSyncVersion")),
            )
        } catch (error: Exception) {
            // E.g. the Keystore key was invalidated; the session can't be recovered, so sign in again.
            android.util.Log.w(TAG, "Saved session is unreadable", error)
            null
        }
    }

    private suspend fun startRoomList(sync: org.matrix.rustcomponents.sdk.SyncService) {
        val service = sync.roomListService()
        roomListService = service
        roomListStateHandle = service.state(
            object : org.matrix.rustcomponents.sdk.RoomListServiceStateListener {
                override fun onUpdate(state: org.matrix.rustcomponents.sdk.RoomListServiceState) {
                    _roomListServiceState.value = state
                }
            },
        )
        val list = service.allRooms()
        roomList = list
        val generation = synchronized(roomEntries) { roomListGeneration }
        val result = list.entriesWithDynamicAdapters(
            20u,
            object : org.matrix.rustcomponents.sdk.RoomListEntriesListener {
                override fun onUpdate(updates: List<org.matrix.rustcomponents.sdk.RoomListEntriesUpdate>) {
                    repositoryScope.launch {
                        // toMatrixRoom suspends: the lock keeps a later batch from starting on a stale list, and the
                        // batch works on a copy so a sign-out meanwhile can't be overwritten by a late result.
                        roomEntriesLock.withLock {
                            val current = synchronized(roomEntries) {
                                if (generation != roomListGeneration) return@withLock
                                roomEntries.toMutableList()
                            }
                            for (update in updates) {
                                when (update) {
                                    is org.matrix.rustcomponents.sdk.RoomListEntriesUpdate.Reset -> {
                                        current.clear()
                                        for (room in update.values) current.add(toMatrixRoom(room))
                                    }
                                    is org.matrix.rustcomponents.sdk.RoomListEntriesUpdate.Append -> {
                                        for (room in update.values) current.add(toMatrixRoom(room))
                                    }
                                    is org.matrix.rustcomponents.sdk.RoomListEntriesUpdate.PushBack ->
                                        current.add(toMatrixRoom(update.value))
                                    is org.matrix.rustcomponents.sdk.RoomListEntriesUpdate.PushFront ->
                                        current.add(0, toMatrixRoom(update.value))
                                    is org.matrix.rustcomponents.sdk.RoomListEntriesUpdate.Insert ->
                                        current.add(update.index.toInt().coerceIn(0, current.size), toMatrixRoom(update.value))
                                    is org.matrix.rustcomponents.sdk.RoomListEntriesUpdate.Set -> {
                                        val index = update.index.toInt()
                                        if (index in current.indices) current[index] = toMatrixRoom(update.value)
                                    }
                                    is org.matrix.rustcomponents.sdk.RoomListEntriesUpdate.Remove -> {
                                        val index = update.index.toInt()
                                        if (index in current.indices) current.removeAt(index)
                                    }
                                    is org.matrix.rustcomponents.sdk.RoomListEntriesUpdate.PopBack ->
                                        if (current.isNotEmpty()) current.removeAt(current.lastIndex)
                                    is org.matrix.rustcomponents.sdk.RoomListEntriesUpdate.PopFront ->
                                        if (current.isNotEmpty()) current.removeAt(0)
                                    is org.matrix.rustcomponents.sdk.RoomListEntriesUpdate.Truncate -> {
                                        val size = update.length.toInt().coerceAtLeast(0)
                                        if (size < current.size) current.subList(size, current.size).clear()
                                    }
                                    org.matrix.rustcomponents.sdk.RoomListEntriesUpdate.Clear -> current.clear()
                                }
                            }
                            synchronized(roomEntries) {
                                if (generation != roomListGeneration) return@withLock
                                roomEntries.clear()
                                roomEntries.addAll(current)
                                publishRooms(current)
                            }
                        }
                    }
                }
            },
        )
        roomEntriesResult = result
        val controller = result.controller()
        roomEntriesController = controller
        controller.setFilter(org.matrix.rustcomponents.sdk.RoomListEntriesDynamicFilterKind.Joined)
        roomEntriesHandle = result.entriesStream()
    }

    private suspend fun toMatrixRoom(room: Room): MatrixRoom {
        val info = try {
            room.roomInfo()
        } catch (error: Exception) {
            android.util.Log.w(TAG, "Room info unavailable for ${room.id()}", error)
            null
        }
        val unread = info?.let { maxOf(it.numUnreadMessages, it.notificationCount).toLong() } ?: 0L
        val direct = room.isDirect()
        val (snippet, timestamp) = summarize(room.latestEvent())
        val matrixRoom = MatrixRoom(
            id = room.id(),
            name = room.displayName().orEmpty().ifBlank { room.id() },
            encrypted = room.isEncrypted(),
            direct = direct,
            lastMessage = snippet,
            lastTimestampMillis = timestamp,
            unreadCount = unread.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
            callActive = info?.hasRoomCall == true,
            callParticipants = info?.activeRoomCallParticipants.orEmpty(),
            callVideo = when (val consensus = info?.activeRoomCallConsensusIntent) {
                is RtcCallIntentConsensus.Full -> consensus.v1 == RtcCallIntent.VIDEO
                is RtcCallIntentConsensus.Partial -> consensus.intent == RtcCallIntent.VIDEO
                else -> false
            },
            pinned = info?.isFavourite == true,
            archived = info?.isLowPriority == true,
            markedUnread = info?.isMarkedUnread == true,
            dmUserId = if (direct) info?.heroes?.singleOrNull()?.userId else null,
            avatarUrl = info?.avatarUrl ?: if (direct) info?.heroes?.singleOrNull()?.avatarUrl else null,
        )
        synchronized(locallyCreatedRooms) { locallyCreatedRooms.remove(matrixRoom.id) }
        return matrixRoom
    }

    /** Snippet + timestamp for the conversation list, Messages-style ("You: …" for own messages). */
    private fun summarize(latest: LatestEventValue): Pair<String?, Long?> = when (latest) {
        is LatestEventValue.Remote -> describe(latest.content, latest.isOwn) to latest.timestamp.toLong()
        is LatestEventValue.Local -> describe(latest.content, own = true) to latest.timestamp.toLong()
        is LatestEventValue.RemoteInvite -> "Invitation" to latest.timestamp.toLong()
        else -> null to null
    }

    private fun describe(content: TimelineItemContent, own: Boolean): String? {
        if (content is TimelineItemContent.RtcNotification) {
            val kind = if (content.callIntent == CALL_INTENT_VIDEO) "Video call" else "Voice call"
            return when {
                content.activeMembers.isNotEmpty() -> "$kind in progress"
                content.declinedBy.isNotEmpty() -> "Call declined"
                own -> "You: $kind"
                else -> kind
            }
        }
        val msgLike = content as? TimelineItemContent.MsgLike ?: return null
        val body = when (val kind = msgLike.content.kind) {
            is MsgLikeKind.Message -> messageSummary(kind.content.msgType)
            is MsgLikeKind.Sticker -> "Sticker"
            is MsgLikeKind.UnableToDecrypt -> "Encrypted message"
            is MsgLikeKind.Redacted -> "Message deleted"
            else -> null
        } ?: return null
        return if (own) "You: $body" else body
    }

    /** Re-reads one room's summary (e.g. after a read receipt) without waiting for the next list diff. */
    private fun refreshRoom(roomId: String) {
        repositoryScope.launch {
            val updated = try {
                val room = client?.getRoom(roomId) ?: return@launch
                room.use { toMatrixRoom(it) }
            } catch (error: Exception) {
                android.util.Log.w(TAG, "Could not refresh $roomId", error)
                return@launch
            }
            roomEntriesLock.withLock {
                synchronized(roomEntries) {
                    val index = roomEntries.indexOfFirst { it.id == roomId }
                    if (index < 0) return@withLock
                    roomEntries[index] = updated
                    publishRooms(roomEntries.toList())
                }
            }
        }
    }

    private fun mergeLocallyCreatedRooms(rooms: List<MatrixRoom>): List<MatrixRoom> {
        synchronized(locallyCreatedRooms) {
            val deduped = LinkedHashMap<String, MatrixRoom>(rooms.size)
            for (room in rooms) deduped[room.id] = room
            for (local in locallyCreatedRooms.values) deduped.putIfAbsent(local.id, local)
            return deduped.values.toList()
        }
    }

    /**
     * Publishes the conversation list (caller holds the [roomEntries] monitor) with the local changes sync hasn't
     * echoed back yet: tags and unread state set here, conversations being deleted. A pending change is dropped
     * once the room shows it, or after [PENDING_ROOM_FLAGS_MS] when sync never does (a newer change elsewhere won).
     */
    private fun publishRooms(rooms: List<MatrixRoom>) {
        val merged = mergeLocallyCreatedRooms(rooms)
        pendingRemovals.retainAll(merged.mapTo(HashSet(merged.size)) { it.id })
        val at = now()
        pendingRoomFlags.values.removeAll { at - it.sinceMillis > PENDING_ROOM_FLAGS_MS }
        _joinedRooms.value = merged.mapNotNull { room ->
            if (room.id in pendingRemovals) return@mapNotNull null
            val pending = pendingRoomFlags[room.id] ?: return@mapNotNull room
            val shown = pending.applyTo(room)
            if (shown == room) pendingRoomFlags.remove(room.id)
            shown
        }
    }

    /** Shows [change] on [roomIds] at once, runs [action] on each room, and takes the change back if one fails. */
    private suspend fun updateRooms(
        roomIds: List<String>,
        change: (PendingRoomFlags) -> PendingRoomFlags,
        action: suspend (Room) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val sdk = requireClient()
        synchronized(roomEntries) {
            for (id in roomIds) pendingRoomFlags[id] = change(pendingRoomFlags[id] ?: PendingRoomFlags())
            publishRooms(roomEntries.toList())
        }
        try {
            for (id in roomIds) {
                val room = sdk.getRoom(id) ?: error("Conversation is unavailable.")
                room.use { action(it) }
            }
        } catch (error: Throwable) {
            synchronized(roomEntries) {
                roomIds.forEach { pendingRoomFlags.remove(it) }
                publishRooms(roomEntries.toList())
            }
            throw error
        }
    }

    /**
     * Archives conversations (the `m.lowpriority` tag) out of the main list, or brings them back. New messages
     * keep an archived conversation archived; its notifications follow its push rules as before. The SDK drops
     * the `m.favourite` tag when it sets `m.lowpriority`, so archiving unpins.
     */
    suspend fun setArchived(roomIds: List<String>, archived: Boolean) =
        updateRooms(roomIds, { it.copy(archived = archived, pinned = if (archived) false else it.pinned, sinceMillis = now()) }) { room ->
            room.setIsLowPriority(archived, null)
        }

    /** Pins conversations to the top of the list (the `m.favourite` tag, which replaces `m.lowpriority`), or unpins them. */
    suspend fun setPinned(roomIds: List<String>, pinned: Boolean) =
        updateRooms(roomIds, { it.copy(pinned = pinned, archived = if (pinned) false else it.archived, sinceMillis = now()) }) { room ->
            room.setIsFavourite(pinned, null)
        }

    /** Marks conversations unread (`m.marked_unread`), or read: flag cleared and a read receipt on the newest event. */
    suspend fun setMarkedUnread(roomIds: List<String>, unread: Boolean) =
        updateRooms(roomIds, { it.copy(unread = unread, sinceMillis = now()) }) { room ->
            room.setUnreadFlag(unread)
            if (!unread) room.markAsRead(ReceiptType.READ)
        }

    /** [setRoomMuted] for several conversations. */
    suspend fun setRoomsMuted(roomIds: List<String>, muted: Boolean) {
        for (id in roomIds) setRoomMuted(id, muted)
    }

    /** Deletes conversations from this account: leaves each room and forgets it, so it is gone from every device's list. */
    suspend fun deleteConversations(roomIds: List<String>) = withContext(Dispatchers.IO) {
        val sdk = requireClient()
        synchronized(roomEntries) {
            pendingRemovals += roomIds
            publishRooms(roomEntries.toList())
        }
        try {
            for (id in roomIds) {
                val room = sdk.getRoom(id) ?: continue
                room.use {
                    if (it.membership() != Membership.LEFT && it.membership() != Membership.BANNED) it.leave()
                    it.forget()
                }
                synchronized(locallyCreatedRooms) { locallyCreatedRooms.remove(id) }
                MessageNotifier.cancel(appContext, id)
            }
        } catch (error: Throwable) {
            synchronized(roomEntries) {
                pendingRemovals -= roomIds.toSet()
                publishRooms(roomEntries.toList())
            }
            throw error
        }
    }

    /** Blocks [userId] (adds them to `m.ignored_user_list`): their messages stop reaching this account. */
    suspend fun blockUser(userId: String) = withContext(Dispatchers.IO) {
        val sdk = requireClient()
        _ignoredUsers.value = _ignoredUsers.value + userId
        try {
            sdk.ignoreUser(userId)
        } catch (error: Throwable) {
            _ignoredUsers.value = _ignoredUsers.value - userId
            throw error
        }
    }

    suspend fun unblockUser(userId: String) = withContext(Dispatchers.IO) {
        val sdk = requireClient()
        _ignoredUsers.value = _ignoredUsers.value - userId
        try {
            sdk.unignoreUser(userId)
        } catch (error: Throwable) {
            _ignoredUsers.value = _ignoredUsers.value + userId
            throw error
        }
    }

    /** Re-reads the blocked users the client holds for this account and returns them. */
    internal suspend fun refreshIgnoredUsers(): Set<String> = withContext(Dispatchers.IO) {
        val sdk = requireClient()
        val ignored = sdk.ignoredUsers().toSet()
        if (client === sdk) _ignoredUsers.value = ignored
        ignored
    }

    /**
     * Re-reads the starred messages from the store (where sync keeps the account data current) and returns what
     * the store holds. A write from this device that sync hasn't brought back yet stays shown for [STARRED_ECHO_MS].
     */
    suspend fun refreshStarred(): Map<String, List<String>> = withContext(Dispatchers.IO) {
        val sdk = requireClient()
        val stored = parseStarred(sdk.accountData(STARRED_EVENT_TYPE))
        starredLock.withLock {
            val written = starredWrite
            if (written == null || written.first == stored || now() - written.second >= STARRED_ECHO_MS) {
                starredWrite = null
                if (client === sdk) _starredEvents.value = stored
            }
        }
        stored
    }

    /** Stars or unstars [eventId] in [roomId], in account data so every session of this account sees it. */
    suspend fun setStarred(roomId: String, eventId: String, starred: Boolean) = withContext(Dispatchers.IO) {
        val sdk = requireClient()
        starredLock.withLock {
            val current = _starredEvents.value
            val ids = current[roomId].orEmpty() - eventId
            val next = if (starred) current + (roomId to ids + eventId) else if (ids.isEmpty()) current - roomId else current + (roomId to ids)
            sdk.setAccountData(STARRED_EVENT_TYPE, starredJson(next))
            starredWrite = next to now()
            _starredEvents.value = next
        }
    }

    /** The starred messages of [roomId], most recently starred first, from the loaded timeline or fetched by ID. */
    suspend fun starredMessages(roomId: String): List<StarredMessage> = withContext(Dispatchers.IO) {
        refreshStarred()
        val ids = _starredEvents.value[roomId].orEmpty().asReversed()
        if (ids.isEmpty()) return@withContext emptyList()
        val ownUserId = ownUserId()
        val loaded = _messages.value.associateBy { it.id }
        val room = requireClient().getRoom(roomId) ?: error("Conversation is unavailable.")
        room.use {
            ids.map { id ->
                loaded[id]?.let { message ->
                    StarredMessage(id, message.senderName ?: message.sender, message.own, messagePreviewText(message), message.timestampMillis)
                } ?: try {
                    room.loadOrFetchEvent(id).use { event ->
                        val content = (event.content() as? TimelineEventContent.MessageLike)?.content
                        StarredMessage(
                            eventId = id,
                            sender = event.senderId(),
                            own = event.senderId() == ownUserId,
                            text = (content as? MessageLikeEventContent.RoomMessage)?.let { messageSummary(it.messageType) },
                            timestampMillis = event.timestamp().toLong(),
                        )
                    }
                } catch (error: Exception) {
                    android.util.Log.w(TAG, "Starred event $id unavailable", error)
                    StarredMessage(id, sender = "", own = false, text = null, timestampMillis = null)
                }
            }
        }
    }

    suspend fun isDirectRoom(room: Room): Boolean = withContext(Dispatchers.IO) { room.isDirect() }
    suspend fun createEncryptedDirectRoom(matrixUserId: String): String = withContext(Dispatchers.IO) {
        val recipient = requireMatrixUserId(matrixUserId)
        val displayName = recipient.substringAfter('@').substringBefore(':')
        createEncryptedRoom(name = null, invite = listOf(recipient), direct = true, localName = displayName)
    }

    /** Creates a private, end-to-end encrypted (non-DM) room and invites every member. */
    suspend fun createEncryptedGroup(name: String, userIds: List<String>): String = withContext(Dispatchers.IO) {
        val members = userIds.map(::requireMatrixUserId).distinct()
        require(members.isNotEmpty()) { "Add at least one person to the group." }
        val groupName = name.trim().ifBlank { null }
        val localName = groupName ?: members.joinToString(", ") { it.substringAfter('@').substringBefore(':') }
        createEncryptedRoom(name = groupName, invite = members, direct = false, localName = localName)
    }

    private fun requireMatrixUserId(value: String): String {
        val userId = value.trim()
        require(isMatrixUserId(userId)) { "Enter a Matrix user ID such as @alice:example.org." }
        return userId
    }

    /**
     * DMs use `trusted_private_chat`, so both people have the creator's power level: either can ring the other
     * (a MatrixRTC ring mentions the room, which push rules only honour from senders allowed to notify the room)
     * and publish call memberships. Groups keep `private_chat` but let every member publish a call membership.
     */
    private suspend fun createEncryptedRoom(name: String?, invite: List<String>, direct: Boolean, localName: String): String {
        val roomId = requireClient().createRoom(
            CreateRoomParameters(
                name = name,
                topic = null,
                isEncrypted = true,
                isDirect = direct,
                visibility = RoomVisibility.Private,
                preset = if (direct) RoomPreset.TRUSTED_PRIVATE_CHAT else RoomPreset.PRIVATE_CHAT,
                invite = invite,
                avatar = null,
                powerLevelContentOverride = if (direct) null else GROUP_POWER_LEVELS,
                joinRuleOverride = null,
                historyVisibilityOverride = null,
                canonicalAlias = null,
                isSpace = false,
            ),
        )
        val createdRoom = MatrixRoom(roomId, localName, encrypted = true, direct = direct)
        synchronized(locallyCreatedRooms) {
            if (_joinedRooms.value.none { it.id == roomId }) locallyCreatedRooms[roomId] = createdRoom
            _joinedRooms.value = mergeLocallyCreatedRooms(_joinedRooms.value)
        }
        return roomId
    }

    suspend fun openEncryptedRoom(roomId: String): Room = withContext(Dispatchers.IO) {
        val room = requireClient().getRoom(roomId) ?: error("Conversation is unavailable.")
        check(room.isEncrypted()) { "This room is not end-to-end encrypted and cannot be opened for messaging." }
        room
    }

    /** The room's live timeline; the SDK's default one tracks everyone's read receipts (so own messages can show "Read"). */
    suspend fun timeline(room: Room): Timeline = withContext(Dispatchers.IO) {
        check(room.isEncrypted()) { "This room is not end-to-end encrypted and cannot be opened for messaging." }
        room.timeline()
    }

    suspend fun observeTimeline(timeline: Timeline) = withContext(Dispatchers.IO) {
        timelineHandle?.close()
        timelineItems.clear()
        callMemberChanges.clear()
        replyDetailsRequested.clear()
        observedTimeline = timeline
        _messages.value = emptyList()
        timelineHandle = timeline.addListener(object : org.matrix.rustcomponents.sdk.TimelineListener {
            override fun onUpdate(diffs: List<org.matrix.rustcomponents.sdk.TimelineDiff>) {
                for (diff in diffs) {
                    when (diff) {
                        is org.matrix.rustcomponents.sdk.TimelineDiff.Reset -> {
                            timelineItems.clear()
                            timelineItems.addAll(diff.values)
                        }
                        is org.matrix.rustcomponents.sdk.TimelineDiff.Append ->
                            timelineItems.addAll(diff.values)
                        is org.matrix.rustcomponents.sdk.TimelineDiff.PushBack ->
                            timelineItems.add(diff.value)
                        is org.matrix.rustcomponents.sdk.TimelineDiff.PushFront ->
                            timelineItems.add(0, diff.value)
                        is org.matrix.rustcomponents.sdk.TimelineDiff.Insert ->
                            timelineItems.add(diff.index.toInt().coerceIn(0, timelineItems.size), diff.value)
                        is org.matrix.rustcomponents.sdk.TimelineDiff.Set -> {
                            val index = diff.index.toInt()
                            if (index in timelineItems.indices) timelineItems[index] = diff.value
                        }
                        is org.matrix.rustcomponents.sdk.TimelineDiff.Remove -> {
                            val index = diff.index.toInt()
                            if (index in timelineItems.indices) timelineItems.removeAt(index)
                        }
                        is org.matrix.rustcomponents.sdk.TimelineDiff.PopBack ->
                            if (timelineItems.isNotEmpty()) timelineItems.removeAt(timelineItems.lastIndex)
                        is org.matrix.rustcomponents.sdk.TimelineDiff.PopFront ->
                            if (timelineItems.isNotEmpty()) timelineItems.removeAt(0)
                        is org.matrix.rustcomponents.sdk.TimelineDiff.Truncate -> {
                            val size = diff.length.toInt().coerceAtLeast(0)
                            if (size < timelineItems.size) timelineItems.subList(size, timelineItems.size).clear()
                        }
                        is org.matrix.rustcomponents.sdk.TimelineDiff.Clear -> timelineItems.clear()
                    }
                }
                _messages.value = buildMessages()
            }
        })
    }

    /**
     * Follows who else is typing in [room] (`subscribeToTypingNotifications`) into [typingUsers] until another
     * conversation is observed or the session closes.
     */
    suspend fun observeTyping(room: Room) = withContext(Dispatchers.IO) {
        typingHandle?.close()
        _typingUsers.value = emptyList()
        val ownUserId = ownUserId()
        var handle: TaskHandle? = null
        handle = room.subscribeToTypingNotifications(object : org.matrix.rustcomponents.sdk.TypingNotificationsListener {
            override fun call(typingUserIds: List<String>) {
                val others = typingUserIds.filter { it != ownUserId }
                repositoryScope.launch {
                    val names = others.map { userId ->
                        try {
                            room.memberDisplayName(userId)?.takeIf { it.isNotBlank() } ?: userId
                        } catch (error: Exception) {
                            userId
                        }
                    }
                    if (typingHandle === handle) _typingUsers.value = names
                }
            }
        })
        typingHandle = handle
    }

    /**
     * Tells the room this account is (or stopped) typing, in order on the repository scope so the UI never waits on
     * the network for it. The SDK only sends a new notice when the state changes or the previous one is about to
     * expire; failures are only logged (a missed notice lapses on its own).
     */
    fun setTyping(room: Room, typing: Boolean) {
        repositoryScope.launch {
            try {
                if (room.isEncrypted()) room.typingNotice(typing)
            } catch (error: Exception) {
                android.util.Log.w(TAG, "Typing notice could not be sent", error)
            }
        }
    }

    /**
     * Messages and call rows for the open timeline. A call row's outcome comes from the room's call memberships
     * (`org.matrix.msc3401.call.member` state in the timeline) between its notification and the next one.
     */
    private fun buildMessages(): List<MatrixMessage> {
        // Events with their timeline items, whose unique ID keys the rows.
        val items = ArrayList<org.matrix.rustcomponents.sdk.TimelineItem>(timelineItems.size)
        val events = ArrayList<org.matrix.rustcomponents.sdk.EventTimelineItem>(timelineItems.size)
        for (item in timelineItems) {
            val event = item.asEvent() ?: continue
            items += item
            events += event
        }
        val changes = ArrayList<CallMemberChange>()
        val ringTimes = ArrayList<Long>()
        for (event in events) {
            when (val content = event.content) {
                is TimelineItemContent.State ->
                    if ((content.content as? OtherState.Custom)?.eventType in CALL_MEMBER_EVENT_TYPES) callMemberChangeOf(event)?.let(changes::add)
                is TimelineItemContent.RtcNotification -> ringTimes += event.timestamp.toLong()
                else -> Unit
            }
        }
        val ownUserId = ownUserId()
        var ring = 0
        return events.indices.mapNotNull { index ->
            val event = events[index]
            if (event.content is TimelineItemContent.RtcNotification) {
                ring += 1
                callMessage(items[index], event, ringTimes.getOrNull(ring), ownUserId, changes)
            } else {
                toMessage(items[index], event, ownUserId)
            }
        }
    }

    /** Call-member changes by event ID: each event's JSON is read once, though every diff rebuilds the list. */
    private val callMemberChanges = HashMap<String, CallMemberChange?>()

    private fun callMemberChangeOf(event: org.matrix.rustcomponents.sdk.EventTimelineItem): CallMemberChange? {
        val eventId = (event.eventOrTransactionId as? EventOrTransactionId.EventId)?.eventId ?: return null
        return callMemberChanges.getOrPut(eventId) {
            try {
                event.lazyProvider.latestJson()?.let(::callMemberChange)
            } catch (error: Exception) {
                android.util.Log.w(TAG, "Call membership $eventId unreadable", error)
                null
            }
        }
    }

    private fun callMessage(
        item: org.matrix.rustcomponents.sdk.TimelineItem,
        event: org.matrix.rustcomponents.sdk.EventTimelineItem,
        nextRingMillis: Long?,
        ownUserId: String?,
        changes: List<CallMemberChange>,
    ): MatrixMessage? {
        val content = event.content as? TimelineItemContent.RtcNotification ?: return null
        val id = (event.eventOrTransactionId as? EventOrTransactionId.EventId)?.eventId ?: return null
        val timestamp = event.timestamp.toLong()
        val (answered, duration) = callOutcome(timestamp, nextRingMillis, event.sender, ownUserId, changes)
        return MatrixMessage(
            id = id,
            sender = event.sender,
            text = "",
            own = event.isOwn,
            timestampMillis = timestamp,
            reactions = emptyList(),
            call = CallEvent(
                video = content.callIntent == CALL_INTENT_VIDEO,
                outgoing = event.isOwn,
                ongoing = content.activeMembers.isNotEmpty(),
                joined = content.isJoined,
                declined = content.declinedBy.isNotEmpty(),
                answered = answered,
                durationMillis = duration,
            ),
            key = item.uniqueId().id,
        )
    }

    private fun toMessage(item: org.matrix.rustcomponents.sdk.TimelineItem, event: org.matrix.rustcomponents.sdk.EventTimelineItem, ownUserId: String?): MatrixMessage? {
        val content = event.content as? TimelineItemContent.MsgLike ?: return null
        val kind = content.content.kind
        if (kind !is MsgLikeKind.Message && kind !is MsgLikeKind.UnableToDecrypt && kind !is MsgLikeKind.Redacted) return null
        val key = item.uniqueId().id
        val id = when (val identifier = event.eventOrTransactionId) {
            is EventOrTransactionId.EventId -> identifier.eventId
            is EventOrTransactionId.TransactionId -> identifier.transactionId
            else -> return null
        }
        val status = when (event.localSendState) {
            is EventSendState.NotSentYet -> DeliveryStatus.Sending
            is EventSendState.SendingFailed -> DeliveryStatus.Failed
            else -> DeliveryStatus.Sent
        }
        val senderName = (event.senderProfile as? ProfileDetails.Ready)?.displayName?.takeIf { it.isNotBlank() }
        // Receipts the SDK placed on this event; the sender's own (implicit) one isn't a "read".
        val readBy = event.readReceipts
            .filterKeys { it != ownUserId && it != event.sender }
            .mapValues { (_, receipt) -> receipt.timestamp?.toLong() ?: 0L }
        if (kind is MsgLikeKind.Redacted) {
            return MatrixMessage(
                id = id,
                sender = event.sender,
                text = "",
                own = event.isOwn,
                timestampMillis = event.timestamp.toLong(),
                reactions = emptyList(),
                status = status,
                senderName = senderName,
                deleted = true,
                readBy = readBy,
                key = key,
            )
        }
        if (kind is MsgLikeKind.UnableToDecrypt) {
            // Kept in the timeline as a placeholder; the SDK swaps in the decrypted event once the key arrives.
            return MatrixMessage(
                id = id,
                sender = event.sender,
                text = "",
                own = event.isOwn,
                timestampMillis = event.timestamp.toLong(),
                reactions = event.reactions.map { it.key },
                status = status,
                decryptionFailure = decryptionFailureOf(kind.msg),
                senderName = senderName,
                readBy = readBy,
                key = key,
            )
        }
        val message = (kind as MsgLikeKind.Message).content
        val attachment = attachmentOf(message.msgType)
        val text = when (val type = message.msgType) {
            is MessageType.Image -> type.content.caption.orEmpty()
            is MessageType.Video -> type.content.caption.orEmpty()
            is MessageType.Audio -> type.content.caption.orEmpty()
            is MessageType.File -> type.content.caption.orEmpty()
            else -> message.body
        }
        return MatrixMessage(
            id = id,
            sender = event.sender,
            text = text,
            own = event.isOwn,
            timestampMillis = event.timestamp.toLong(),
            reactions = event.reactions.map { it.key },
            attachment = attachment,
            status = status,
            senderName = senderName,
            replyTo = content.content.inReplyTo?.let { replyQuoteOf(it, (event.eventOrTransactionId as? EventOrTransactionId.EventId)?.eventId, ownUserId) },
            edited = message.isEdited,
            editable = event.isEditable,
            readBy = readBy,
            key = key,
        )
    }

    /** The quoted message of a reply; asks the SDK to fetch it (once) when it isn't in the local timeline yet. */
    private fun replyQuoteOf(details: org.matrix.rustcomponents.sdk.InReplyToDetails, replyEventId: String?, ownUserId: String?): ReplyQuote {
        val quotedId = details.eventId()
        return when (val quoted = details.event()) {
            is EmbeddedEventDetails.Ready -> ReplyQuote(
                eventId = quotedId,
                sender = (quoted.senderProfile as? ProfileDetails.Ready)?.displayName?.takeIf { it.isNotBlank() } ?: quoted.sender,
                own = quoted.sender == ownUserId,
                text = describe(quoted.content, own = false),
            )
            is EmbeddedEventDetails.Unavailable -> {
                val timeline = observedTimeline
                if (replyEventId != null && timeline != null && replyDetailsRequested.add(replyEventId)) {
                    repositoryScope.launch {
                        try {
                            timeline.fetchDetailsForEvent(replyEventId)
                        } catch (error: Exception) {
                            android.util.Log.w(TAG, "Reply details for $replyEventId unavailable", error)
                        }
                    }
                }
                ReplyQuote(quotedId, sender = null, own = false, text = null)
            }
            else -> ReplyQuote(quotedId, sender = null, own = false, text = null)
        }
    }

    private fun decryptionFailureOf(message: EncryptedMessage): DecryptionFailure =
        when ((message as? EncryptedMessage.MegolmV1AesSha2)?.cause) {
            UtdCause.SENT_BEFORE_WE_JOINED -> DecryptionFailure.SentBeforeJoined
            UtdCause.VERIFICATION_VIOLATION -> DecryptionFailure.SenderIdentityChanged
            UtdCause.UNSIGNED_DEVICE, UtdCause.UNKNOWN_DEVICE -> DecryptionFailure.InsecureSenderDevice
            UtdCause.HISTORICAL_MESSAGE_AND_BACKUP_IS_DISABLED -> DecryptionFailure.BackupDisabled
            UtdCause.HISTORICAL_MESSAGE_AND_DEVICE_IS_UNVERIFIED -> DecryptionFailure.UnverifiedDevice
            UtdCause.WITHHELD_FOR_UNVERIFIED_OR_INSECURE_DEVICE -> DecryptionFailure.WithheldForUnverified
            UtdCause.WITHHELD_BY_SENDER -> DecryptionFailure.WithheldBySender
            UtdCause.UNKNOWN, null -> DecryptionFailure.MissingKey
        }

    private fun attachmentOf(type: MessageType): MatrixAttachment? = when (type) {
        is MessageType.Image -> type.content.let { image ->
            MatrixAttachment(
                kind = AttachmentKind.Image,
                filename = image.filename,
                mimetype = image.info?.mimetype,
                sizeBytes = image.info?.size?.toLong(),
                durationMillis = null,
                width = image.info?.width?.toInt(),
                height = image.info?.height?.toInt(),
                sourceJson = image.source.toJson(),
                thumbnailSourceJson = image.info?.thumbnailSource?.toJson(),
            )
        }
        is MessageType.Video -> type.content.let { video ->
            MatrixAttachment(
                kind = AttachmentKind.Video,
                filename = video.filename,
                mimetype = video.info?.mimetype,
                sizeBytes = video.info?.size?.toLong(),
                durationMillis = video.info?.duration?.toMillis(),
                width = video.info?.width?.toInt(),
                height = video.info?.height?.toInt(),
                sourceJson = video.source.toJson(),
                thumbnailSourceJson = video.info?.thumbnailSource?.toJson(),
            )
        }
        is MessageType.Audio -> type.content.let { audio ->
            MatrixAttachment(
                kind = if (audio.voice != null) AttachmentKind.Voice else AttachmentKind.Audio,
                filename = audio.filename,
                mimetype = audio.info?.mimetype,
                sizeBytes = audio.info?.size?.toLong(),
                durationMillis = (audio.info?.duration ?: audio.audio?.duration)?.toMillis(),
                width = null,
                height = null,
                sourceJson = audio.source.toJson(),
                thumbnailSourceJson = null,
                // MSC3246 waveform samples are 0..1024.
                waveform = audio.audio?.waveform.orEmpty().map { (it.toInt() / 1024f).coerceIn(0f, 1f) },
            )
        }
        is MessageType.File -> type.content.let { file ->
            MatrixAttachment(
                kind = AttachmentKind.File,
                filename = file.filename,
                mimetype = file.info?.mimetype,
                sizeBytes = file.info?.size?.toLong(),
                durationMillis = null,
                width = null,
                height = null,
                sourceJson = file.source.toJson(),
                thumbnailSourceJson = file.info?.thumbnailSource?.toJson(),
            )
        }
        else -> null
    }

    /** Sends [text]; with [replyToEventId] it goes out as a reply (`m.in_reply_to`) to that event. */
    suspend fun sendText(timeline: Timeline, room: Room, text: String, replyToEventId: String? = null) = withContext(Dispatchers.IO) {
        check(room.isEncrypted()) { "Sending is disabled because this room is not encrypted." }
        require(text.isNotBlank())
        val content = timeline.createMessageContent(MessageType.Text(TextMessageContent(text, null)))
            ?: error("The Matrix SDK could not create a text message.")
        if (replyToEventId != null) timeline.sendReply(content, replyToEventId) else timeline.send(content)
    }

    /** Replaces the text of own message [messageId] (`m.replace`); local echoes are edited in the send queue. */
    suspend fun editText(timeline: Timeline, room: Room, messageId: String, text: String) = withContext(Dispatchers.IO) {
        check(room.isEncrypted()) { "Editing is disabled because this room is not encrypted." }
        require(text.isNotBlank())
        val content = timeline.createMessageContent(MessageType.Text(TextMessageContent(text, null)))
            ?: error("The Matrix SDK could not create a text message.")
        timeline.edit(eventOrTransactionId(messageId), EditedContent.RoomMessage(content))
    }

    /** Deletes [messageId] for everyone (redaction); an unsent local echo is just dropped from the send queue. */
    suspend fun redact(timeline: Timeline, room: Room, messageId: String) = withContext(Dispatchers.IO) {
        check(room.isEncrypted()) { "Deleting is disabled because this room is not encrypted." }
        timeline.redactEvent(eventOrTransactionId(messageId), null)
    }

    /**
     * Sends a copy of [eventId]'s content (text, or media re-using the original encrypted upload) to the encrypted
     * room [targetRoomId], without the reply relation.
     */
    suspend fun forward(source: Timeline, eventId: String, targetRoomId: String) = withContext(Dispatchers.IO) {
        val item = source.getEventTimelineItemByEventId(eventId)
        val message = ((item.content as? TimelineItemContent.MsgLike)?.content?.kind as? MsgLikeKind.Message)?.content
            ?: error("This message can't be forwarded.")
        val target = openEncryptedRoom(targetRoomId)
        target.timeline().send(contentWithoutRelationFromMessage(message))
    }

    /**
     * Paginates back until [eventId] is in the open timeline (a reply's original, a search hit), at most
     * [maxPages] pages; false when the start of the room was reached without finding it.
     */
    suspend fun paginateUntil(timeline: Timeline, eventId: String, maxPages: Int = 10): Boolean = withContext(Dispatchers.IO) {
        repeat(maxPages) {
            if (_messages.value.any { it.id == eventId }) return@withContext true
            if (timeline.paginateBackwards(50u.toUShort())) return@withContext _messages.value.any { it.id == eventId }
        }
        _messages.value.any { it.id == eventId }
    }

    private val linkPreviews = android.util.LruCache<String, java.util.Optional<LinkPreview>>(64)

    /**
     * OpenGraph preview of [url] from the homeserver (`/media/preview_url` through `Client.getUrlPreview`), with
     * its image downloaded; null when the server has no preview (previews disabled, unreachable page, no title).
     */
    suspend fun linkPreview(url: String): LinkPreview? = withContext(Dispatchers.IO) {
        linkPreviews.get(url)?.let { return@withContext it.orElse(null) }
        val preview = try {
            val json = org.json.JSONObject(requireClient().getUrlPreview(url, null))
            fun field(name: String) = json.optString(name).trim().takeIf { it.isNotEmpty() }
            val title = field("og:title")
            val description = field("og:description")
            if (title == null && description == null) {
                null
            } else {
                val image = field("og:image")?.takeIf { it.startsWith("mxc://") }?.let { mxc ->
                    try {
                        mediaFile(MediaSource.fromUrl(mxc).use { it.toJson() }, "link-preview")
                    } catch (error: Exception) {
                        null
                    }
                }
                LinkPreview(url = url, title = title, description = description, siteName = field("og:site_name"), image = image)
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            android.util.Log.i(TAG, "No preview for $url: ${error.message}")
            null
        }
        linkPreviews.put(url, java.util.Optional.ofNullable(preview))
        preview
    }

    private fun eventOrTransactionId(id: String): EventOrTransactionId =
        if (id.startsWith("$")) EventOrTransactionId.EventId(id) else EventOrTransactionId.TransactionId(id)

    /**
     * Copies a picked document/photo into the cache (the SDK uploads from a file path), then sends it as
     * m.image / m.video (with a generated thumbnail) or m.file depending on its MIME type.
     */
    suspend fun sendAttachment(timeline: Timeline, room: Room, uri: Uri) = withContext(Dispatchers.IO) {
        check(room.isEncrypted()) { "Attachments are disabled because this room is not encrypted." }
        // A per-send directory keeps the original file name, which the SDK uses as the event body.
        val workDir = appContext.cacheDir.resolve("outgoing/${java.util.UUID.randomUUID()}").apply { mkdirs() }
        try {
            val picked = copyToCache(appContext, uri, workDir)
            sendFileAttachment(timeline, picked.file, picked.mimeType, workDir)
        } finally {
            workDir.deleteRecursively()
        }
    }

    /**
     * Sends a share the user confirmed: its text, then each staged copy through the attachment path (m.image /
     * m.video with a thumbnail, or m.file). The caller deletes the staged copies.
     */
    internal suspend fun sendShared(timeline: Timeline, room: Room, text: String?, files: List<SharedFile>) = withContext(Dispatchers.IO) {
        check(room.isEncrypted()) { "Sharing is disabled because this room is not encrypted." }
        if (!text.isNullOrBlank()) sendText(timeline, room, text)
        for (shared in files) {
            // Thumbnails go beside the copy, not over a shared file that happens to be called thumbnail.jpg.
            val workDir = (shared.file.parentFile ?: appContext.cacheDir).resolve("work").apply { mkdirs() }
            sendFileAttachment(timeline, shared.file, shared.mimeType, workDir)
        }
    }

    /** Sends an already-materialised file; [workDir] receives any generated thumbnail. */
    private suspend fun sendFileAttachment(timeline: Timeline, file: File, mimetype: String, workDir: File) {
        val size = file.length().toULong()
        val params = UploadParameters(UploadSource.File(file.absolutePath), null, null, null, null, null)
        val handle = when {
            mimetype.startsWith("image/") -> {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                val width = bounds.outWidth.takeIf { it > 0 }
                val height = bounds.outHeight.takeIf { it > 0 }
                val thumbnail = if (width != null && height != null && maxOf(width, height) > THUMBNAIL_PX) {
                    BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sampleSize(width, height, THUMBNAIL_PX) })
                        ?.let { writeThumbnail(it, workDir) }
                } else null
                timeline.sendImage(
                    params,
                    thumbnail?.first,
                    ImageInfo(height?.toULong(), width?.toULong(), mimetype, size, thumbnail?.second, null, null, null),
                )
            }
            mimetype.startsWith("video/") -> {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(file.absolutePath)
                    val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                    val rawWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
                    val rawHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
                    val (width, height) = if (rotation % 180 != 0) rawHeight to rawWidth else rawWidth to rawHeight
                    val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    val thumbnail = retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, THUMBNAIL_PX, THUMBNAIL_PX)
                        ?.let { writeThumbnail(it, workDir) }
                    timeline.sendVideo(
                        params,
                        thumbnail?.first,
                        VideoInfo(duration?.let(Duration::ofMillis), height?.toULong(), width?.toULong(), mimetype, size, thumbnail?.second, null, null),
                    )
                } finally {
                    retriever.release()
                }
            }
            else -> timeline.sendFile(params, FileInfo(mimetype, size, null, null))
        }
        handle.use { it.join() }
    }

    private fun writeThumbnail(bitmap: Bitmap, workDir: File): Pair<UploadSource, ThumbnailInfo> {
        val file = File(workDir, "thumbnail.jpg")
        try {
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it)) }
            return UploadSource.File(file.absolutePath) to
                ThumbnailInfo(bitmap.height.toULong(), bitmap.width.toULong(), "image/jpeg", file.length().toULong())
        } finally {
            bitmap.recycle()
        }
    }

    /** Sends a recorded Ogg/Opus clip as an MSC3245 voice message and deletes the local recording. */
    suspend fun sendVoiceMessage(timeline: Timeline, room: Room, recording: VoiceRecording) = withContext(Dispatchers.IO) {
        try {
            check(room.isEncrypted()) { "Voice messages are disabled because this room is not encrypted." }
            val params = UploadParameters(UploadSource.File(recording.file.absolutePath), null, null, null, null, null)
            val info = AudioInfo(Duration.ofMillis(recording.durationMillis), recording.file.length().toULong(), VOICE_MIMETYPE)
            timeline.sendVoiceMessage(params, info, recording.waveform).use { it.join() }
        } finally {
            recording.file.delete()
        }
    }

    /**
     * Downloads (and, for encrypted rooms, decrypts) media through the SDK into a stable cache file so the
     * UI can decode images, play voice clips and hand files to other apps.
     */
    suspend fun mediaFile(sourceJson: String, filename: String): File = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("SHA-256").digest(sourceJson.toByteArray()).joinToString("") { "%02x".format(it) }
        val file = appContext.cacheDir.resolve("media/${digest.take(32)}/${safeFileName(filename)}")
        if (file.isFile && file.length() > 0) return@withContext file
        val bytes = MediaSource.fromJson(sourceJson).use { requireClient().getMediaContent(it) }
        file.parentFile?.mkdirs()
        val partial = File(file.parentFile, "${file.name}.part")
        partial.writeBytes(bytes)
        check(partial.renameTo(file)) { "Media could not be cached." }
        file
    }

    /** Sends a read receipt for the newest event so the room's unread badge clears; opening also drops a "Mark as unread". */
    suspend fun markAsRead(timeline: Timeline, roomId: String) = withContext(Dispatchers.IO) {
        timeline.markAsRead(ReceiptType.READ)
        client?.getRoom(roomId)?.use { room ->
            if (room.roomInfo().isMarkedUnread) room.setUnreadFlag(false)
        }
        refreshRoom(roomId)
    }

    suspend fun react(timeline: Timeline, room: Room, eventId: String, emoji: String) = withContext(Dispatchers.IO) {
        check(room.isEncrypted()) { "Reactions are disabled because this room is not encrypted." }
        timeline.toggleReaction(org.matrix.rustcomponents.sdk.EventOrTransactionId.EventId(eventId), emoji)
    }

    suspend fun paginateOlder(timeline: Timeline): Boolean = withContext(Dispatchers.IO) {
        timeline.paginateBackwards(30u.toUShort())
    }
    /**
     * Turns free-form server input into a homeserver base URL. Explicit URLs keep
     * the strict HTTPS validation; bare server names (and Matrix IDs) go through
     * `.well-known/matrix/client` discovery first, falling back to
     * `https://<server>` when the server publishes no delegation.
     */
    suspend fun resolveHomeserverUrl(value: String): String = withContext(Dispatchers.IO) {
        val input = value.trim()
        require(input.isNotBlank()) { "Enter your homeserver address." }
        val candidate = serverNameFromMxid(input) ?: input
        if ("://" in candidate) return@withContext normalizeHomeserverUrl(candidate)
        val server = candidate.substringBefore('/').trim()
        require(server.isValidServerName()) { "Enter a valid HTTPS homeserver URL." }
        discoverHomeserverBaseUrl(server) ?: "https://$server"
    }

    private fun discoverHomeserverBaseUrl(server: String): String? {
        return try {
            val connection = java.net.URL("https://$server/.well-known/matrix/client").openConnection() as java.net.HttpURLConnection
            try {
                connection.connectTimeout = 8000
                connection.readTimeout = 8000
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("Accept", "application/json")
                if (connection.responseCode != java.net.HttpURLConnection.HTTP_OK) return null
                val body = connection.inputStream.bufferedReader().use { it.readText() }.trim()
                if (body.isEmpty() || body.length > 65536) return null
                val baseUrl = org.json.JSONObject(body).optJSONObject("m.homeserver")?.optString("base_url")?.trim().orEmpty()
                if (baseUrl.isBlank()) return null
                runCatching { normalizeHomeserverUrl(baseUrl) }.getOrNull()
            } finally {
                connection.disconnect()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun normalizeHomeserverUrl(value: String): String {
        val url = URI(value.trim())
        // Plain-HTTP local Synapse (DEVELOPMENT.md): only debug and the test-only `minified` build set this flag,
        // alongside their cleartext network security config. Release builds never accept it.
        val localDebugHomeserver =
            appContext.resources.getBoolean(R.bool.allow_local_dev_homeserver) &&
            url.scheme == "http" &&
            url.port == 8008 &&
            when (url.host?.lowercase()) {
                "10.0.2.2", "127.0.0.1", "localhost" -> true
                else -> false
            }
        require(
            (url.scheme == "https" || localDebugHomeserver) &&
                !url.host.isNullOrBlank() &&
                url.userInfo == null &&
                url.query == null &&
                url.fragment == null &&
                (url.port == -1 || url.port in 1..65535)
        ) {
            "Enter a valid HTTPS homeserver URL."
        }
        return url.normalize().toASCIIString().trimEnd('/')
    }

    private suspend fun createClient(homeserver: String): Client {
        val root = appContext.filesDir.resolve(STORE_DIR).apply { mkdirs() }
        // State and crypto stores in `data`, event and media caches in `store-cache`, all encrypted with the store key.
        // No `sessionPaths` here: it replaces the store configuration with an unencrypted one.
        val store = SqliteStoreBuilder(root.resolve(STORE_DATA_DIR).absolutePath, root.resolve(STORE_CACHE_DIR).absolutePath)
            .key(storeKey())
        return ClientBuilder()
            .homeserverUrl(homeserver)
            .slidingSyncVersionBuilder(SlidingSyncVersionBuilder.NATIVE)
            .sqliteStore(store)
            // Bootstraps this account's cross-signing identity on its first login, which recovery
            // (secret storage) and session verification build on.
            .autoEnableCrossSigning(true)
            .setSessionDelegate(sessionDelegate)
            .build()
    }

    private fun requireClient(): Client = client ?: error("Sign in to a Matrix account first.")

    /** The session's MatrixRTC calls (Element Call), created on first use. */
    fun callSession(): NativeMatrixCallSession = synchronized(this) {
        callSession ?: NativeMatrixCallSession(
            appContext,
            requireClient(),
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        ).also { callSession = it }
    }

    /** Room of the call running on this device, if any. */
    internal fun activeCallRoomId(): String? = callSession?.activeRoomId

    /** The SDK room for [roomId] in the live session; the caller closes it. */
    internal fun sdkRoom(roomId: String): Room? = client?.getRoom(roomId)

    /** MatrixRTC transports the homeserver advertises (MSC4143 endpoint, else well-known `rtc_foci`). */
    suspend fun rtcTransports(): List<RtcTransport> = withContext(Dispatchers.IO) {
        requireClient().discoverRtcTransports().orEmpty()
    }

    /** Declines the ring [notificationEventId] in [roomId] (`m.rtc.decline`), restoring the session when cold. */
    suspend fun declineCall(roomId: String, notificationEventId: String) = withContext(Dispatchers.IO) {
        check(restore()) { "Sign in to a Matrix account first." }
        val room = requireClient().getRoom(roomId) ?: error("Conversation is unavailable.")
        room.use { it.declineCall(notificationEventId) }
    }

    // ---- Conversation details, profile and sessions (phase 6) ----

    /** An avatar (`mxc://`) as a homeserver thumbnail about [sizePx] square, decoded for display. */
    suspend fun avatarBitmap(url: String, sizePx: Int): Bitmap? = withContext(Dispatchers.IO) {
        val side = sizePx.coerceIn(32, 512).toULong()
        val bytes = MediaSource.fromUrl(url).use { requireClient().getMediaThumbnail(it, side, side) }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    /** Everything conversation details shows: name, topic, people, notification level, what this account may change. */
    suspend fun roomDetails(roomId: String): RoomDetails = withContext(Dispatchers.IO) {
        val sdk = requireClient()
        val room = sdk.getRoom(roomId) ?: error("Conversation is unavailable.")
        room.use {
            val info = room.roomInfo()
            val ownUserId = room.ownUserId()
            val iterator = try {
                room.members()
            } catch (error: Exception) {
                android.util.Log.w(TAG, "Members of $roomId unavailable from the server; using the store", error)
                room.membersNoSync()
            }
            val members = iterator.use {
                buildList {
                    while (true) {
                        val chunk = iterator.nextChunk(100u)
                        if (chunk.isNullOrEmpty()) break
                        addAll(chunk)
                    }
                }
            }
            val people = members.filter { it.membership is MembershipState.Join || it.membership is MembershipState.Invite }
                .map { member ->
                    RoomPerson(
                        userId = member.userId,
                        displayName = member.displayName?.takeIf { it.isNotBlank() },
                        avatarUrl = member.avatarUrl,
                        invited = member.membership is MembershipState.Invite,
                        role = when (member.suggestedRoleForPowerLevel) {
                            RoomMemberRole.CREATOR, RoomMemberRole.ADMINISTRATOR -> PersonRole.Admin
                            RoomMemberRole.MODERATOR -> PersonRole.Moderator
                            else -> PersonRole.Member
                        },
                        isMe = member.userId == ownUserId,
                    )
                }
                // You first, then joined before invited, then by name.
                .sortedWith(compareBy<RoomPerson>({ !it.isMe }, { it.invited }, { it.name.lowercase() }))
            val direct = room.isDirect()
            val dmUserId = if (direct) info.heroes.singleOrNull()?.userId ?: people.singleOrNull { !it.isMe }?.userId else null
            val identity = dmUserId?.let { userId ->
                try {
                    requireEncryption().userIdentity(userId, true)?.use { identity ->
                        when {
                            identity.hasVerificationViolation() -> IdentityState.Changed
                            identity.isVerified() -> IdentityState.Verified
                            else -> IdentityState.NotVerified
                        }
                    } ?: IdentityState.Unknown
                } catch (error: Exception) {
                    android.util.Log.w(TAG, "Identity of $userId unavailable", error)
                    IdentityState.Unknown
                }
            }
            val settings = requireNotificationSettings()
            val mode = settings.getRoomNotificationSettings(roomId, room.isEncrypted(), room.activeMembersCount() <= 2uL).mode
            room.getPowerLevels().use { power ->
                RoomDetails(
                    roomId = roomId,
                    name = room.displayName().orEmpty().ifBlank { roomId },
                    topic = room.topic()?.takeIf { it.isNotBlank() },
                    avatarUrl = info.avatarUrl ?: if (direct) info.heroes.singleOrNull()?.avatarUrl else null,
                    direct = direct,
                    encrypted = room.isEncrypted(),
                    people = people,
                    notifications = mode.toLevel(),
                    canInvite = power.canOwnUserInvite(),
                    canRename = power.canOwnUserSendState(StateEventType.RoomName),
                    canSetTopic = power.canOwnUserSendState(StateEventType.RoomTopic),
                    canSetAvatar = power.canOwnUserSendState(StateEventType.RoomAvatar),
                    dmUserId = dmUserId,
                    dmIdentity = identity,
                )
            }
        }
    }

    /** Sets the room's push rule for [level] (All / Mentions / Off); Off is the same MUTE rule as Mute. */
    suspend fun setRoomNotifications(roomId: String, level: NotificationLevel) = withContext(Dispatchers.IO) {
        val settings = requireNotificationSettings()
        settings.setRoomNotificationMode(roomId, level.toMode())
        if (level == NotificationLevel.Off) MessageNotifier.cancel(appContext, roomId)
        _mutedRoomIds.value = if (level == NotificationLevel.Off) _mutedRoomIds.value + roomId else _mutedRoomIds.value - roomId
    }

    /** Invites [userId] into [roomId] (Details → Add people). */
    suspend fun inviteToRoom(roomId: String, userId: String) = withContext(Dispatchers.IO) {
        val invitee = requireMatrixUserId(userId)
        val room = requireClient().getRoom(roomId) ?: error("Conversation is unavailable.")
        room.use { it.inviteUserById(invitee) }
    }

    /** Renames a group (`m.room.name`); a blank name removes it so members' names show instead. */
    suspend fun renameRoom(roomId: String, name: String) = withContext(Dispatchers.IO) {
        val room = requireClient().getRoom(roomId) ?: error("Conversation is unavailable.")
        room.use { it.setName(name.trim()) }
        refreshRoom(roomId)
    }

    suspend fun setRoomTopic(roomId: String, topic: String) = withContext(Dispatchers.IO) {
        val room = requireClient().getRoom(roomId) ?: error("Conversation is unavailable.")
        room.use { it.setTopic(topic.trim()) }
    }

    /** Uploads a picked photo as the group's avatar (`m.room.avatar`). */
    suspend fun setRoomAvatar(roomId: String, uri: Uri) = withContext(Dispatchers.IO) {
        val image = avatarJpeg(uri)
        val room = requireClient().getRoom(roomId) ?: error("Conversation is unavailable.")
        room.use {
            it.uploadAvatar(
                AVATAR_MIMETYPE,
                image.bytes,
                ImageInfo(image.height.toULong(), image.width.toULong(), AVATAR_MIMETYPE, image.bytes.size.toULong(), null, null, null, null),
            )
        }
        refreshRoom(roomId)
    }

    /** The encrypted DM with [userId] (opening an existing one), creating it when there is none. */
    suspend fun directRoomWith(userId: String): String = withContext(Dispatchers.IO) {
        val existing = requireClient().getDmRoom(userId)?.use { room -> room.id().takeIf { room.isEncrypted() } }
        existing ?: createEncryptedDirectRoom(userId)
    }

    /** Re-reads this account's display name and avatar from the homeserver. */
    suspend fun refreshOwnProfile(): OwnProfile = withContext(Dispatchers.IO) {
        val sdk = requireClient()
        val profile = sdk.getProfile(sdk.userId())
        OwnProfile(profile.displayName, profile.avatarUrl).also { if (client === sdk) _ownProfile.value = it }
    }

    /** Sets this account's display name (it also updates the account's membership in every room). */
    suspend fun setDisplayName(name: String) = withContext(Dispatchers.IO) {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "Enter a name." }
        requireClient().setDisplayName(trimmed)
        _ownProfile.value = (_ownProfile.value ?: OwnProfile(null, null)).copy(displayName = trimmed)
        refreshOwnProfile()
    }

    suspend fun setProfileAvatar(uri: Uri) = withContext(Dispatchers.IO) {
        val image = avatarJpeg(uri)
        requireClient().uploadAvatar(AVATAR_MIMETYPE, image.bytes)
        refreshOwnProfile()
    }

    suspend fun removeProfileAvatar() = withContext(Dispatchers.IO) {
        requireClient().removeAvatar()
        _ownProfile.value = _ownProfile.value?.copy(avatarUrl = null)
        refreshOwnProfile()
    }

    private class AvatarImage(val bytes: ByteArray, val width: Int, val height: Int)

    /** A picked photo scaled to at most [AVATAR_PX] and re-encoded as JPEG for upload. */
    private fun avatarJpeg(uri: Uri): AvatarImage {
        val resolver = appContext.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: error("The photo could not be read.")
        check(bounds.outWidth > 0 && bounds.outHeight > 0) { "That file isn't a photo." }
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, AVATAR_PX) }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: error("The photo could not be read.")
        val scale = minOf(1f, AVATAR_PX.toFloat() / maxOf(decoded.width, decoded.height))
        val bitmap = if (scale < 1f) {
            Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true).also { decoded.recycle() }
        } else decoded
        try {
            val out = java.io.ByteArrayOutputStream()
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)) { "The photo could not be prepared." }
            return AvatarImage(out.toByteArray(), bitmap.width, bitmap.height)
        } finally {
            bitmap.recycle()
        }
    }

    /** This account's sessions (devices) from the homeserver, this one first, then most recently active. */
    suspend fun devices(): List<SessionDevice> = withContext(Dispatchers.IO) {
        val current = requireClient().deviceId()
        val (code, text) = clientApi("GET", "/_matrix/client/v3/devices", null)
        check(code in 200..299) { apiError(text) ?: "Your sessions couldn't be loaded." }
        val list = org.json.JSONObject(text).optJSONArray("devices") ?: org.json.JSONArray()
        List(list.length()) { index ->
            val device = list.getJSONObject(index)
            val id = device.getString("device_id")
            SessionDevice(
                id = id,
                name = device.optString("display_name").takeIf { it.isNotBlank() && !device.isNull("display_name") },
                lastSeenMillis = device.optLong("last_seen_ts").takeIf { it > 0 },
                lastSeenIp = device.optString("last_seen_ip").takeIf { it.isNotBlank() && !device.isNull("last_seen_ip") },
                current = id == current,
            )
        }.sortedWith(compareBy<SessionDevice>({ !it.current }, { -(it.lastSeenMillis ?: 0L) }))
    }

    /**
     * Signs another session out (`POST /delete_devices`). The homeserver asks to confirm it's you: with [password]
     * (and the [session] it handed out) for password accounts, or in a browser for others, after which the call is
     * repeated with just the [session]. Accounts on an OAuth (MAS) server delete sessions on the account page.
     */
    suspend fun signOutDevice(deviceId: String, password: String? = null, session: String? = null): DeviceSignOut = withContext(Dispatchers.IO) {
        val sdk = requireClient()
        check(deviceId != sdk.deviceId()) { "Use Sign out to sign this device out." }
        if (sdk.session().oauthData != null) {
            val url = sdk.accountUrl(AccountManagementAction.DeviceDelete(deviceId)) ?: error("Your account page isn't available.")
            return@withContext DeviceSignOut.Browser(url, session = null)
        }
        val body = org.json.JSONObject().put("devices", org.json.JSONArray().put(deviceId))
        if (session != null) {
            val auth = org.json.JSONObject().put("session", session)
            if (password != null) {
                auth.put("type", AUTH_PASSWORD)
                    .put("identifier", org.json.JSONObject().put("type", "m.id.user").put("user", sdk.userId()))
                    .put("password", password)
            }
            body.put("auth", auth)
        }
        val (code, text) = clientApi("POST", "/_matrix/client/v3/delete_devices", body.toString())
        if (code in 200..299) return@withContext DeviceSignOut.Done
        val json = runCatching { org.json.JSONObject(text) }.getOrNull()
        val authSession = json?.optString("session")?.takeIf { it.isNotBlank() }
        if (code != 401 || json == null || authSession == null) error(apiError(text) ?: "This session couldn't be signed out.")
        val flows = json.optJSONArray("flows") ?: org.json.JSONArray()
        val stages = List(flows.length()) { index ->
            val stageList = flows.getJSONObject(index).optJSONArray("stages") ?: org.json.JSONArray()
            List(stageList.length()) { stageList.getString(it) }
        }
        when {
            stages.any { it == listOf(AUTH_PASSWORD) } ->
                DeviceSignOut.Password(authSession, wrongPassword = password != null && json.has("errcode"))
            stages.firstOrNull()?.firstOrNull() != null -> {
                val stage = stages.first().first()
                val base = sdk.session().homeserverUrl.trimEnd('/')
                DeviceSignOut.Browser("$base/_matrix/client/v3/auth/${encodePath(stage)}/fallback/web?session=${encodePath(authSession)}", authSession)
            }
            else -> error(apiError(text) ?: "Your homeserver didn't say how to confirm it's you.")
        }
    }

    /** A client-server API call with this session's access token; returns the status code and body. */
    private fun clientApi(method: String, path: String, body: String?): Pair<Int, String> {
        val session = requireClient().session()
        val connection = java.net.URL(session.homeserverUrl.trimEnd('/') + path).openConnection() as java.net.HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 20_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("Authorization", "Bearer ${session.accessToken}")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            return code to (stream?.bufferedReader()?.use { it.readText() }.orEmpty())
        } finally {
            connection.disconnect()
        }
    }

    private fun apiError(text: String): String? =
        runCatching { org.json.JSONObject(text).optString("error") }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun encodePath(value: String): String = java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    /** AES-GCM key held by Android Keystore; it seals the SDK store key and the saved session. */
    private fun wrappingKey(): javax.crypto.SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return keyStore.getKey(STORE_KEY_ALIAS, null) as? javax.crypto.SecretKey
            ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").run {
                init(android.security.keystore.KeyGenParameterSpec.Builder(
                    STORE_KEY_ALIAS,
                    android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT
                ).setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build())
                generateKey()
            }
    }

    /** Encrypts with the Keystore key; the result is Base64 of IV followed by ciphertext. */
    private fun seal(plain: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
        return Base64.encodeToString(cipher.iv + cipher.doFinal(plain), Base64.NO_WRAP)
    }

    private fun unseal(sealed: String): ByteArray {
        val packed = Base64.decode(sealed, Base64.NO_WRAP)
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, packed.copyOfRange(0, 12)))
            doFinal(packed, 12, packed.size - 12)
        }
    }

    private fun storeKey(): ByteArray {
        prefs().getString(KEY_STORE_KEY, null)?.let { return unseal(it) }
        val raw = ByteArray(32).also(java.security.SecureRandom()::nextBytes)
        check(prefs().edit().putString(KEY_STORE_KEY, seal(raw)).commit())
        return raw
    }

    companion object {
        private const val PREFS = "matrix_session_locator"
        private const val KEY_STORE_KEY = "sdk_store_key"
        private const val KEY_SESSION = "sdk_session"
        private const val TAG = "MatrixRepository"
        private const val THUMBNAIL_PX = 800
        internal const val STORE_DIR = "matrix"
        internal const val STORE_DATA_DIR = "data"
        private const val STORE_CACHE_DIR = "store-cache"
        internal const val LEGACY_UNENCRYPTED_STORE_DIR = "crypto-store"
        private const val STORE_KEY_ALIAS = "send.matrix.store-key"
        private const val BACKUP_UPLOAD_TIMEOUT_MS = 30_000L
        private const val REPLY_SEND_TIMEOUT_MS = 30_000L
        /** Pusher app ID registered with the homeserver for this app's UnifiedPush endpoint. */
        const val PUSHER_APP_ID = "dev.send.matrix"
        const val VOICE_MIMETYPE = "audio/ogg"
        /** Global account data holding this account's starred messages: `{"rooms": {roomId: [eventId, …]}}`. */
        const val STARRED_EVENT_TYPE = "dev.send.starred"
        private const val AVATAR_MIMETYPE = "image/jpeg"
        /** Longest edge of uploaded avatars. */
        private const val AVATAR_PX = 1024
        private const val AUTH_PASSWORD = "m.login.password"
        private const val STARRED_ECHO_MS = 60_000L
        private const val PENDING_ROOM_FLAGS_MS = 60_000L

        private fun now(): Long = android.os.SystemClock.elapsedRealtime()

        internal fun parseStarred(json: String?): Map<String, List<String>> {
            if (json.isNullOrBlank()) return emptyMap()
            val root = runCatching { org.json.JSONObject(json) }.getOrNull() ?: return emptyMap()
            // Content as stored; tolerate a full event (`{"type": …, "content": …}`) as well.
            val content = root.optJSONObject("content")?.takeIf { root.has("type") } ?: root
            val rooms = content.optJSONObject("rooms") ?: return emptyMap()
            return buildMap {
                for (roomId in rooms.keys()) {
                    val events = rooms.optJSONArray(roomId) ?: continue
                    val ids = (0 until events.length()).mapNotNull { index -> events.optString(index).takeIf { it.startsWith("$") } }.distinct()
                    if (ids.isNotEmpty()) put(roomId, ids)
                }
            }
        }

        internal fun starredJson(starred: Map<String, List<String>>): String {
            val rooms = org.json.JSONObject()
            for ((roomId, ids) in starred) rooms.put(roomId, org.json.JSONArray(ids))
            return org.json.JSONObject().put("rooms", rooms).toString()
        }
        @Volatile private var instance: MatrixRepository? = null

        fun get(context: Context): MatrixRepository = instance ?: synchronized(this) {
            instance ?: MatrixRepository(context.applicationContext).also { instance = it }
        }
    }
}

/**
 * Power levels for new groups: Synapse's `private_chat` defaults, plus MatrixRTC call memberships open to every
 * member so anyone can start or join the group's call. The override's `events` replaces the default map, so
 * the defaults are repeated.
 */
private val GROUP_POWER_LEVELS = PowerLevels(
    usersDefault = null,
    eventsDefault = null,
    stateDefault = null,
    ban = null,
    kick = null,
    redact = null,
    invite = null,
    notifications = null,
    users = emptyMap(),
    events = mapOf(
        "m.room.name" to 50,
        "m.room.power_levels" to 100,
        "m.room.history_visibility" to 100,
        "m.room.canonical_alias" to 50,
        "m.room.avatar" to 50,
        "m.room.tombstone" to 100,
        "m.room.server_acl" to 100,
        "m.room.encryption" to 100,
        "org.matrix.msc3401.call.member" to 0,
        "m.call.member" to 0,
    ),
)

internal fun isMatrixUserId(value: String): Boolean =
    value.startsWith("@") && value.indexOf(':') > 1 && !value.any(Char::isWhitespace)

/** Text for a message in the conversation list and in notifications: media by kind, everything else by body. */
internal fun messageSummary(type: MessageType): String = when (type) {
    is MessageType.Image -> "Photo"
    is MessageType.Video -> "Video"
    is MessageType.Audio -> if (type.content.voice != null) "Voice message" else "Audio"
    is MessageType.File -> type.content.filename
    is MessageType.Text -> type.content.body
    is MessageType.Emote -> type.content.body
    is MessageType.Notice -> type.content.body
    is MessageType.Location -> type.content.body
    is MessageType.Gallery -> type.content.body
    is MessageType.Other -> type.body
}

/** Strips path separators and control characters so a remote/picked name is safe as a cache file name. */
internal fun safeFileName(name: String): String =
    name.substringAfterLast('/').filter { it >= ' ' && it != '\\' && it != ':' }.trim().trimStart('.').take(120).ifBlank { "attachment" }

/** Largest power-of-two BitmapFactory sample size that keeps the longest edge at or above [targetPx]. */
internal fun sampleSize(width: Int, height: Int, targetPx: Int): Int {
    var sample = 1
    while (maxOf(width, height) / (sample * 2) >= targetPx) sample *= 2
    return sample
}
