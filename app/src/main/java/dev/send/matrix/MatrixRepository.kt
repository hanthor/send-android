package dev.send.matrix

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.content.pm.ApplicationInfo
import android.util.Base64
import java.net.URI
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import java.io.File
import java.security.MessageDigest
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
import org.matrix.rustcomponents.sdk.SlidingSyncVersionBuilder
import org.matrix.rustcomponents.sdk.MessageType
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
import org.matrix.rustcomponents.sdk.MsgLikeKind
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
)

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
)

/** The signed-in Matrix account and this device's session. */
data class AccountInfo(val userId: String, val deviceId: String)

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
    @Volatile private var syncService: org.matrix.rustcomponents.sdk.SyncService? = null
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
    private val _roomListServiceState = MutableStateFlow(org.matrix.rustcomponents.sdk.RoomListServiceState.INITIAL)
    val roomListServiceState: StateFlow<org.matrix.rustcomponents.sdk.RoomListServiceState> = _roomListServiceState.asStateFlow()
    private val _messages = MutableStateFlow<List<MatrixMessage>>(emptyList())
    val messages: StateFlow<List<MatrixMessage>> = _messages.asStateFlow()

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

    suspend fun restore(): Boolean = withContext(Dispatchers.IO) {
        if (client != null) return@withContext true
        val homeserver = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_HOMESERVER, null)
            ?: return@withContext false
        val sdk = createClient(homeserver)
        return@withContext try {
            val session = sdk.session()
            sdk.restoreSession(session)
            client = sdk
            true
        } catch (error: Exception) {
            sdk.close()
            throw error
        }
    }


    suspend fun qrLogin(homeserver: String, qrPayload: String) = withContext(Dispatchers.IO) {
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
        val sdk = createClient(resolveHomeserverUrl(homeserver))
        try {
            val handler = sdk.startSsoLogin("dev.send.matrix://sso/callback", null)
            ssoClient?.close()
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
        val sdk = createClient(resolveHomeserverUrl(effectiveServerInput(homeserver, user)))
        try {
            sdk.login(user, password, "Send Android", null)
            replaceAuthenticatedClient(sdk)
            appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_HOMESERVER, sdk.homeserver()).apply()
        } catch (error: Exception) {
            sdk.close()
            throw error
        }
    }

    private fun saveAuthenticatedClient(sdk: Client) {
        replaceAuthenticatedClient(sdk)
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_HOMESERVER, sdk.homeserver()).apply()
    }

    private fun replaceAuthenticatedClient(sdk: Client) {
        closeSessionResources()
        client = sdk
    }

    private fun closeSessionResources() {
        timelineHandle?.close()
        timelineHandle = null
        closeSecurityResources()
        closeRoomListResources()
        syncService?.close()
        syncService = null
        callSession?.close()
        callSession = null
        client?.close()
        client = null
        synchronized(locallyCreatedRooms) { locallyCreatedRooms.clear() }
        _joinedRooms.value = emptyList()
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

    fun close() {
        closeSessionResources()
        ssoClient?.close()
        ssoClient = null
        ssoHandler = null
        repositoryScope.cancel()
    }

    suspend fun startSync() = withContext(Dispatchers.IO) {
        if (syncService != null) return@withContext
        val sdk = requireClient()
        val service = sdk.syncService().finish()
        try {
            startRoomList(service)
            service.start()
            syncService = service
            startSecurity(sdk)
        } catch (error: Exception) {
            closeSecurityResources()
            closeRoomListResources()
            service.close()
            syncService = null
            throw error
        }
    }

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
    }

    private fun wipeLocalSession() {
        appContext.filesDir.resolve(STORE_DIR).deleteRecursively()
        appContext.cacheDir.resolve("media").deleteRecursively()
        check(
            appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove(KEY_HOMESERVER)
                .remove(KEY_STORE_KEY)
                .commit(),
        ) { "The signed-out session could not be cleared." }
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(STORE_KEY_ALIAS)
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
        val result = list.entriesWithDynamicAdapters(
            20u,
            object : org.matrix.rustcomponents.sdk.RoomListEntriesListener {
                override fun onUpdate(updates: List<org.matrix.rustcomponents.sdk.RoomListEntriesUpdate>) {
                    repositoryScope.launch {
                        val current = _joinedRooms.value.toMutableList()
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
                        _joinedRooms.value = mergeLocallyCreatedRooms(current)
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
        val (snippet, timestamp) = summarize(room.latestEvent())
        val matrixRoom = MatrixRoom(
            id = room.id(),
            name = room.displayName().orEmpty().ifBlank { room.id() },
            encrypted = room.isEncrypted(),
            direct = room.isDirect(),
            lastMessage = snippet,
            lastTimestampMillis = timestamp,
            unreadCount = unread.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
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
        val msgLike = content as? TimelineItemContent.MsgLike ?: return null
        val body = when (val kind = msgLike.content.kind) {
            is MsgLikeKind.Message -> when (val type = kind.content.msgType) {
                is MessageType.Image -> "Photo"
                is MessageType.Video -> "Video"
                is MessageType.Audio -> if (type.content.voice != null) "Voice message" else "Audio"
                is MessageType.File -> type.content.filename
                else -> kind.content.body
            }
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
            _joinedRooms.value = _joinedRooms.value.map { if (it.id == roomId) updated else it }
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

    private suspend fun createEncryptedRoom(name: String?, invite: List<String>, direct: Boolean, localName: String): String {
        val roomId = requireClient().createRoom(
            CreateRoomParameters(
                name = name,
                topic = null,
                isEncrypted = true,
                isDirect = direct,
                visibility = RoomVisibility.Private,
                preset = RoomPreset.PRIVATE_CHAT,
                invite = invite,
                avatar = null,
                powerLevelContentOverride = null,
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

    suspend fun timeline(room: Room): Timeline = withContext(Dispatchers.IO) {
        check(room.isEncrypted()) { "This room is not end-to-end encrypted and cannot be opened for messaging." }
        room.timeline()
    }

    suspend fun observeTimeline(timeline: Timeline) = withContext(Dispatchers.IO) {
        timelineHandle?.close()
        timelineItems.clear()
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
                _messages.value = timelineItems.mapNotNull(::toMessage)
            }
        })
    }

    private fun toMessage(item: org.matrix.rustcomponents.sdk.TimelineItem): MatrixMessage? {
        val event = item.asEvent() ?: return null
        val content = event.content as? TimelineItemContent.MsgLike ?: return null
        val kind = content.content.kind
        if (kind !is MsgLikeKind.Message && kind !is MsgLikeKind.UnableToDecrypt) return null
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
        )
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

    suspend fun sendText(timeline: Timeline, room: Room, text: String) = withContext(Dispatchers.IO) {
        check(room.isEncrypted()) { "Sending is disabled because this room is not encrypted." }
        require(text.isNotBlank())
        val content = timeline.createMessageContent(MessageType.Text(TextMessageContent(text, null)))
            ?: error("The Matrix SDK could not create a text message.")
        timeline.send(content)
    }

    /**
     * Copies a picked document/photo into the cache (the SDK uploads from a file path), then sends it as
     * m.image / m.video (with a generated thumbnail) or m.file depending on its MIME type.
     */
    suspend fun sendAttachment(timeline: Timeline, room: Room, uri: Uri) = withContext(Dispatchers.IO) {
        check(room.isEncrypted()) { "Attachments are disabled because this room is not encrypted." }
        val resolver = appContext.contentResolver
        val mimetype = resolver.getType(uri) ?: "application/octet-stream"
        val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
        // A per-send directory keeps the original file name, which the SDK uses as the event body.
        val workDir = appContext.cacheDir.resolve("outgoing/${java.util.UUID.randomUUID()}").apply { mkdirs() }
        try {
            val file = File(workDir, safeFileName(displayName ?: uri.lastPathSegment ?: "attachment"))
            val input = resolver.openInputStream(uri) ?: error("The selected file could not be read.")
            input.use { source -> file.outputStream().use { source.copyTo(it) } }
            sendFileAttachment(timeline, file, mimetype, workDir)
        } finally {
            workDir.deleteRecursively()
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

    /** Sends a read receipt for the newest event so the room's unread badge clears. */
    suspend fun markAsRead(timeline: Timeline, roomId: String) = withContext(Dispatchers.IO) {
        timeline.markAsRead(ReceiptType.READ)
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
        val localDebugHomeserver =
            (appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0 &&
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
        val store = root.resolve("sdk-store.db").absolutePath
        val crypto = root.resolve("crypto-store").absolutePath
        return ClientBuilder()
            .homeserverUrl(homeserver)
            .slidingSyncVersionBuilder(SlidingSyncVersionBuilder.NATIVE)
            .sqliteStore(SqliteStoreBuilder(store, "").key(storeKey()))
            .sessionPaths(root.resolve("cache").absolutePath, crypto)
            // Bootstraps this account's cross-signing identity on its first login, which recovery
            // (secret storage) and session verification build on.
            .autoEnableCrossSigning(true)
            .build()
    }

    private fun requireClient(): Client = client ?: error("Sign in to a Matrix account first.")
    fun callSession(): NativeMatrixCallSession = synchronized(this) {
        callSession ?: NativeMatrixCallSession(
            appContext,
            requireClient(),
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        ).also { callSession = it }
    }

    private fun storeKey(): ByteArray {
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val alias = STORE_KEY_ALIAS
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val wrappingKey = keyStore.getKey(alias, null) as? javax.crypto.SecretKey
            ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").run {
                init(android.security.keystore.KeyGenParameterSpec.Builder(
                    alias,
                    android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT
                ).setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build())
                generateKey()
            }
        val encoded = prefs.getString(KEY_STORE_KEY, null)
        if (encoded != null) {
            val packed = Base64.decode(encoded, Base64.NO_WRAP)
            val iv = packed.copyOfRange(0, 12)
            val encryptedKey = packed.copyOfRange(12, packed.size)
            return Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, wrappingKey, GCMParameterSpec(128, iv))
                doFinal(encryptedKey)
            }
        }
        val raw = ByteArray(32).also(java.security.SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey)
        val packed = cipher.iv + cipher.doFinal(raw)
        check(prefs.edit().putString(KEY_STORE_KEY, Base64.encodeToString(packed, Base64.NO_WRAP)).commit())
        return raw
    }

    companion object {
        private const val PREFS = "matrix_session_locator"
        private const val KEY_HOMESERVER = "homeserver"
        private const val KEY_STORE_KEY = "sdk_store_key"
        private const val TAG = "MatrixRepository"
        private const val THUMBNAIL_PX = 800
        private const val STORE_DIR = "matrix"
        private const val STORE_KEY_ALIAS = "send.matrix.store-key"
        private const val BACKUP_UPLOAD_TIMEOUT_MS = 30_000L
        const val VOICE_MIMETYPE = "audio/ogg"
        @Volatile private var instance: MatrixRepository? = null

        fun get(context: Context): MatrixRepository = instance ?: synchronized(this) {
            instance ?: MatrixRepository(context.applicationContext).also { instance = it }
        }
    }
}

internal fun isMatrixUserId(value: String): Boolean =
    value.startsWith("@") && value.indexOf(':') > 1 && !value.any(Char::isWhitespace)

/** Strips path separators and control characters so a remote/picked name is safe as a cache file name. */
internal fun safeFileName(name: String): String =
    name.substringAfterLast('/').filter { it >= ' ' && it != '\\' && it != ':' }.trim().trimStart('.').take(120).ifBlank { "attachment" }

/** Largest power-of-two BitmapFactory sample size that keeps the longest edge at or above [targetPx]. */
internal fun sampleSize(width: Int, height: Int, targetPx: Int): Int {
    var sample = 1
    while (maxOf(width, height) / (sample * 2) >= targetPx) sample *= 2
    return sample
}
