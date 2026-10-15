package dev.send.matrix.notifications

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.send.matrix.MatrixRepository
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.matrix.rustcomponents.sdk.RoomListServiceState
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.UnifiedPush
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

/** How new messages reach this device. */
internal data class PushStatus(
    /** Package of the UnifiedPush distributor Send registered with, if any. */
    val distributor: String?,
    /** UnifiedPush endpoint registered as this device's Matrix pusher (its pushkey). */
    val endpoint: String?,
    /** Matrix push gateway the homeserver posts to for [endpoint]. */
    val gateway: String?,
    /** Why push isn't working, when the last registration failed. */
    val error: String?,
) {
    /** Push is live; otherwise the periodic background sync is scheduled. */
    val pushActive: Boolean get() = endpoint != null
}

/**
 * UnifiedPush first: with a distributor (e.g. ntfy) installed, Send registers for an endpoint and sets it as an
 * HTTP pusher (`Client.setPusher`) whose gateway is the distributor's Matrix gateway. Without one — or until
 * registration succeeds — [BackgroundSyncWorker] runs every 15 minutes. While the process is alive, the SDK
 * sync's notification handler delivers notifications either way.
 */
internal object PushRegistration {
    private const val PREFS = "send_push"
    private const val KEY_ENDPOINT = "endpoint"
    private const val KEY_GATEWAY = "gateway"
    private const val KEY_ERROR = "error"
    private const val BACKGROUND_SYNC_WORK = "background-sync"
    /** Gateway for endpoints whose push server has no Matrix gateway of its own (UnifiedPush spec). */
    const val DEFAULT_MATRIX_GATEWAY = "https://matrix.gateway.unifiedpush.org/_matrix/push/v1/notify"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val _status = MutableStateFlow(PushStatus(null, null, null, null))
    val status: StateFlow<PushStatus> = _status.asStateFlow()

    /** Installed UnifiedPush distributors (package names). */
    fun distributors(context: Context): List<String> = UnifiedPush.getDistributors(context)

    /**
     * Called whenever a session is live. Registers again with the chosen distributor (it answers with the current
     * endpoint, which may have changed); picks the only installed distributor automatically; otherwise keeps the
     * periodic background sync.
     */
    fun start(context: Context) {
        val distributor = UnifiedPush.getAckDistributor(context)
            ?: UnifiedPush.getSavedDistributor(context)
            ?: distributors(context).singleOrNull()?.also { UnifiedPush.saveDistributor(context, it) }
        if (distributor != null) register(context)
        if (prefs(context).getString(KEY_ENDPOINT, null) == null) scheduleBackgroundSync(context) else cancelBackgroundSync(context)
        refreshStatus(context)
    }

    /** The user picked [packageName] in the Notifications dialog. */
    fun useDistributor(context: Context, packageName: String) {
        UnifiedPush.saveDistributor(context, packageName)
        register(context)
        refreshStatus(context)
    }

    /** The user chose background sync: drops the distributor registration and this device's pusher. */
    fun useBackgroundSync(context: Context) {
        val appContext = context.applicationContext
        UnifiedPush.unregister(appContext, INSTANCE)
        UnifiedPush.removeDistributor(appContext)
        scope.launch { onUnregistered(appContext) }
    }

    /** A distributor gave Send an endpoint: register it with the homeserver as this device's pusher. */
    suspend fun onNewEndpoint(context: Context, endpoint: String) = mutex.withLock {
        val prefs = prefs(context)
        val previous = prefs.getString(KEY_ENDPOINT, null)
        try {
            val repository = MatrixRepository.get(context)
            if (!repository.restore()) return@withLock
            val gateway = discoverMatrixGateway(endpoint)
            if (previous != null && previous != endpoint) {
                try {
                    repository.deletePusher(previous)
                } catch (error: Exception) {
                    Log.w(TAG, "Previous pusher could not be removed", error)
                }
            }
            repository.setHttpPusher(endpoint, gateway)
            prefs.edit().putString(KEY_ENDPOINT, endpoint).putString(KEY_GATEWAY, gateway).remove(KEY_ERROR).commit()
            cancelBackgroundSync(context)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Pusher registration failed", error)
            prefs.edit().putString(KEY_ERROR, error.message ?: "The homeserver didn't accept the push endpoint.").commit()
            scheduleBackgroundSync(context)
        } finally {
            refreshStatus(context)
        }
    }

    /** The distributor dropped Send (or the user switched to background sync). */
    suspend fun onUnregistered(context: Context) = mutex.withLock {
        val repository = MatrixRepository.get(context)
        prefs(context).getString(KEY_ENDPOINT, null)?.let { endpoint ->
            try {
                if (repository.restore()) repository.deletePusher(endpoint)
            } catch (error: Exception) {
                Log.w(TAG, "Pusher could not be removed", error)
            }
        }
        prefs(context).edit().remove(KEY_ENDPOINT).remove(KEY_GATEWAY).remove(KEY_ERROR).commit()
        if (repository.isSignedIn) scheduleBackgroundSync(context)
        refreshStatus(context)
    }

    fun onRegistrationFailed(context: Context, reason: FailedReason) {
        val message = when (reason) {
            FailedReason.NETWORK -> "The push distributor couldn't reach its server."
            FailedReason.ACTION_REQUIRED -> "Open your push distributor to finish setting it up."
            FailedReason.VAPID_REQUIRED, FailedReason.INTERNAL_ERROR -> "The push distributor refused the registration."
        }
        prefs(context).edit().putString(KEY_ERROR, message).commit()
        scheduleBackgroundSync(context)
        refreshStatus(context)
    }

    /** Before a server-side sign-out: deletes this device's pusher while the session still works. */
    suspend fun removePusher(context: Context, repository: MatrixRepository) {
        val endpoint = prefs(context).getString(KEY_ENDPOINT, null) ?: return
        try {
            repository.deletePusher(endpoint)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Pusher could not be removed before sign-out", error)
        }
    }

    /** After sign-out: the distributor registration and background sync end with the session. */
    fun onSignedOut(context: Context) {
        if (UnifiedPush.getAckDistributor(context) != null) UnifiedPush.unregister(context, INSTANCE)
        prefs(context).edit().clear().commit()
        cancelBackgroundSync(context)
        refreshStatus(context)
    }

    fun refreshStatus(context: Context) {
        val prefs = prefs(context)
        _status.value = PushStatus(
            distributor = UnifiedPush.getAckDistributor(context) ?: UnifiedPush.getSavedDistributor(context),
            endpoint = prefs.getString(KEY_ENDPOINT, null),
            gateway = prefs.getString(KEY_GATEWAY, null),
            error = prefs.getString(KEY_ERROR, null),
        )
    }

    internal fun launch(block: suspend () -> Unit) {
        scope.launch { block() }
    }

    /** Asks the saved distributor for an endpoint; it answers through [SendPushService.onNewEndpoint]. */
    private fun register(context: Context) {
        UnifiedPush.register(context, INSTANCE, "Send", null)
    }

    private fun scheduleBackgroundSync(context: Context) {
        val request = PeriodicWorkRequestBuilder<BackgroundSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(BACKGROUND_SYNC_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    private fun cancelBackgroundSync(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(BACKGROUND_SYNC_WORK)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Send registers a single UnifiedPush instance (one Matrix session per install). */
    private const val INSTANCE = "default"
}

/** `scheme://host[:port]/_matrix/push/v1/notify` on the endpoint's push server, or null for a non-HTTP endpoint. */
internal fun matrixGatewayCandidate(endpoint: String): String? {
    val uri = try {
        URI(endpoint)
    } catch (_: Exception) {
        return null
    }
    val scheme = uri.scheme?.lowercase()
    if ((scheme != "https" && scheme != "http") || uri.host.isNullOrBlank()) return null
    val port = if (uri.port == -1) "" else ":${uri.port}"
    return "$scheme://${uri.host}$port/_matrix/push/v1/notify"
}

/**
 * UnifiedPush Matrix gateway discovery: the endpoint's push server is its own gateway when
 * `GET /_matrix/push/v1/notify` answers `{"unifiedpush":{"gateway":"matrix"}}`; otherwise the public
 * UnifiedPush gateway forwards pushes to the endpoint.
 */
internal suspend fun discoverMatrixGateway(endpoint: String): String = withContext(Dispatchers.IO) {
    val candidate = matrixGatewayCandidate(endpoint) ?: return@withContext PushRegistration.DEFAULT_MATRIX_GATEWAY
    try {
        val connection = URL(candidate).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Accept", "application/json")
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                if (JSONObject(body).optJSONObject("unifiedpush")?.optString("gateway") == "matrix") return@withContext candidate
            }
        } finally {
            connection.disconnect()
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Log.i(TAG, "No Matrix gateway at $candidate", error)
    }
    PushRegistration.DEFAULT_MATRIX_GATEWAY
}

/** The app's UnifiedPush receiver: endpoints become pushers, messages become notifications. */
class SendPushService : PushService() {
    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        val context = applicationContext
        PushRegistration.launch { PushRegistration.onNewEndpoint(context, endpoint.url) }
    }

    override fun onMessage(message: PushMessage, instance: String) {
        val event = parsePushPayload(message.content) ?: return
        PushEventWorker.enqueue(applicationContext, event)
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        PushRegistration.onRegistrationFailed(applicationContext, reason)
    }

    override fun onUnregistered(instance: String) {
        val context = applicationContext
        PushRegistration.launch { PushRegistration.onUnregistered(context) }
    }
}

/** Fetches, decrypts and shows one pushed event; expedited so it runs right away even from a cold start. */
class PushEventWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val roomId = inputData.getString(KEY_ROOM_ID) ?: return Result.failure()
        val eventId = inputData.getString(KEY_EVENT_ID) ?: return Result.failure()
        return try {
            NotificationRouter.fetchAndRoute(applicationContext, roomId, eventId)
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Pushed event $eventId could not be fetched", error)
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val KEY_ROOM_ID = "roomId"
        private const val KEY_EVENT_ID = "eventId"

        internal fun enqueue(context: Context, event: PushedEvent) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "push-${event.eventId}",
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<PushEventWorker>()
                    .setInputData(workDataOf(KEY_ROOM_ID to event.roomId, KEY_EVENT_ID to event.eventId))
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .build(),
            )
        }
    }
}

/**
 * Fallback without push: every 15 minutes, restores the session if needed and (re)starts the SDK sync, whose
 * notification handler posts what arrived, then gives it time to catch up and decrypt. Sync keeps running for
 * as long as Android keeps the process alive.
 */
class BackgroundSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repository = MatrixRepository.get(applicationContext)
        return try {
            if (!repository.restore()) return Result.success()
            repository.startSync()
            withTimeoutOrNull(SYNC_READY_TIMEOUT_MS) { repository.roomListServiceState.first { it == RoomListServiceState.RUNNING } }
            delay(CATCH_UP_MS)
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Background sync failed", error)
            Result.retry()
        }
    }

    private companion object {
        const val SYNC_READY_TIMEOUT_MS = 60_000L
        const val CATCH_UP_MS = 20_000L
    }
}
