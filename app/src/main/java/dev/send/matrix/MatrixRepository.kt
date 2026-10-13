package dev.send.matrix

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Base64
import java.net.URI
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.ClientBuilder
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

data class MatrixRoom(val id: String, val name: String, val encrypted: Boolean, val direct: Boolean)
data class MatrixMessage(
    val id: String,
    val sender: String,
    val text: String,
    val own: Boolean,
    val timestampMillis: Long,
    val reactions: List<String>,
)

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
    private val _roomListServiceState = MutableStateFlow(org.matrix.rustcomponents.sdk.RoomListServiceState.INITIAL)
    val roomListServiceState: StateFlow<org.matrix.rustcomponents.sdk.RoomListServiceState> = _roomListServiceState.asStateFlow()
    private val _messages = MutableStateFlow<List<MatrixMessage>>(emptyList())
    val messages: StateFlow<List<MatrixMessage>> = _messages.asStateFlow()

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
        val sdk = createClient(normalizeHomeserverUrl(homeserver))
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
        val sdk = createClient(normalizeHomeserverUrl(homeserver))
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
        val sdk = createClient(normalizeHomeserverUrl(homeserver))
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
        closeRoomListResources()
        syncService?.close()
        syncService = null
        callSession?.close()
        callSession = null
        client?.close()
        client = null
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
            service.start()
            syncService = service
            startRoomList(service)
        } catch (error: Exception) {
            closeRoomListResources()
            service.close()
            syncService = null
            throw error
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
                        _joinedRooms.value = current
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

    private suspend fun toMatrixRoom(room: Room): MatrixRoom =
        MatrixRoom(room.id(), room.displayName().orEmpty().ifBlank { room.id() }, room.isEncrypted(), room.isDirect())

    suspend fun isDirectRoom(room: Room): Boolean = withContext(Dispatchers.IO) { room.isDirect() }
    suspend fun createEncryptedDirectRoom(matrixUserId: String): String = withContext(Dispatchers.IO) {
        val recipient = matrixUserId.trim()
        require(recipient.startsWith("@") && recipient.indexOf(':') > 1 && !recipient.any(Char::isWhitespace)) {
            "Enter a Matrix user ID such as @alice:example.org."
        }
        requireClient().createRoom(
            CreateRoomParameters(
                name = null,
                topic = null,
                isEncrypted = true,
                isDirect = true,
                visibility = RoomVisibility.Private,
                preset = RoomPreset.PRIVATE_CHAT,
                invite = listOf(recipient),
                avatar = null,
                powerLevelContentOverride = null,
                joinRuleOverride = null,
                historyVisibilityOverride = null,
                canonicalAlias = null,
                isSpace = false,
            ),
        )
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
        val content = event.content as? org.matrix.rustcomponents.sdk.TimelineItemContent.MsgLike ?: return null
        val message = (content.content.kind as? org.matrix.rustcomponents.sdk.MsgLikeKind.Message)?.content ?: return null
        val id = (event.eventOrTransactionId as? org.matrix.rustcomponents.sdk.EventOrTransactionId.EventId)?.eventId
            ?: return null
        return MatrixMessage(id, event.sender, message.body, event.isOwn, event.timestamp.toLong(), event.reactions.map { it.key })
    }

    suspend fun sendText(timeline: Timeline, room: Room, text: String) = withContext(Dispatchers.IO) {
        check(room.isEncrypted()) { "Sending is disabled because this room is not encrypted." }
        require(text.isNotBlank())
        val content = timeline.createMessageContent(MessageType.Text(TextMessageContent(text, null)))
            ?: error("The Matrix SDK could not create a text message.")
        timeline.send(content)
    }

    suspend fun react(timeline: Timeline, room: Room, eventId: String, emoji: String) = withContext(Dispatchers.IO) {
        check(room.isEncrypted()) { "Reactions are disabled because this room is not encrypted." }
        timeline.toggleReaction(org.matrix.rustcomponents.sdk.EventOrTransactionId.EventId(eventId), emoji)
    }

    suspend fun paginateOlder(timeline: Timeline): Boolean = withContext(Dispatchers.IO) {
        timeline.paginateBackwards(30u.toUShort())
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
        val root = appContext.filesDir.resolve("matrix").apply { mkdirs() }
        val store = root.resolve("sdk-store.db").absolutePath
        val crypto = root.resolve("crypto-store").absolutePath
        return ClientBuilder()
            .homeserverUrl(homeserver)
            .sqliteStore(SqliteStoreBuilder(store, "").key(storeKey()))
            .sessionPaths(root.resolve("cache").absolutePath, crypto)
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
        val alias = "send.matrix.store-key"
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
        @Volatile private var instance: MatrixRepository? = null

        fun get(context: Context): MatrixRepository = instance ?: synchronized(this) {
            instance ?: MatrixRepository(context.applicationContext).also { instance = it }
        }
    }
}
