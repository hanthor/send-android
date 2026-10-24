package dev.send.matrix

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** Settings → Choose theme. */
enum class ThemeChoice(val label: String) {
    Light("Light"),
    Dark("Dark"),
    System("System default"),
}

/** Settings → Swipe actions: what swiping a conversation row does in each direction. */
enum class SwipeAction(val label: String) {
    Archive("Archive"),
    Delete("Delete"),
    /** Toggles read / unread (Messages' "Mark as read or unread"). */
    MarkRead("Mark as read or unread"),
    None("None"),
}

/** Both directions of the swipe-actions setting; [start] is a swipe from the leading edge (right, in LTR). */
data class SwipeActions(val start: SwipeAction = SwipeAction.Archive, val end: SwipeAction = SwipeAction.Archive)

/**
 * This device's app preferences (Settings): theme, in-conversation sounds, automatic link previews and swipe
 * actions. Kept in SharedPreferences; the flows let every screen follow a change at once.
 */
class SendSettings private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _theme = MutableStateFlow(
        prefs.getString(KEY_THEME, null)?.let { saved -> ThemeChoice.entries.firstOrNull { it.name == saved } } ?: ThemeChoice.System,
    )
    val theme: StateFlow<ThemeChoice> = _theme.asStateFlow()

    private val _sounds = MutableStateFlow(prefs.getBoolean(KEY_SOUNDS, true))
    /** Messages' "Hear outgoing and incoming message sounds" (in the open conversation). */
    val sounds: StateFlow<Boolean> = _sounds.asStateFlow()

    private val _linkPreviews = MutableStateFlow(prefs.getBoolean(KEY_LINK_PREVIEWS, true))
    /** Automatic previews: fetch homeserver URL previews for links in messages. */
    val linkPreviews: StateFlow<Boolean> = _linkPreviews.asStateFlow()

    private val _swipeActions = MutableStateFlow(
        SwipeActions(
            start = SwipeAction.entries.firstOrNull { it.name == prefs.getString(KEY_SWIPE_START, null) } ?: SwipeAction.Archive,
            end = SwipeAction.entries.firstOrNull { it.name == prefs.getString(KEY_SWIPE_END, null) } ?: SwipeAction.Archive,
        ),
    )
    /** Messages' Settings → Swipe actions. */
    val swipeActions: StateFlow<SwipeActions> = _swipeActions.asStateFlow()

    fun setSwipeActions(actions: SwipeActions) {
        prefs.edit().putString(KEY_SWIPE_START, actions.start.name).putString(KEY_SWIPE_END, actions.end.name).apply()
        _swipeActions.value = actions
    }

    fun setTheme(choice: ThemeChoice) {
        prefs.edit().putString(KEY_THEME, choice.name).apply()
        _theme.value = choice
    }

    fun setSounds(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SOUNDS, enabled).apply()
        _sounds.value = enabled
    }

    fun setLinkPreviews(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LINK_PREVIEWS, enabled).apply()
        _linkPreviews.value = enabled
    }

    companion object {
        private const val PREFS = "send_settings"
        private const val KEY_THEME = "theme"
        private const val KEY_SOUNDS = "conversation_sounds"
        private const val KEY_SWIPE_START = "swipe_start"
        private const val KEY_SWIPE_END = "swipe_end"
        private const val KEY_LINK_PREVIEWS = "link_previews"

        @Volatile private var instance: SendSettings? = null

        fun get(context: Context): SendSettings = instance ?: synchronized(this) {
            instance ?: SendSettings(context).also { instance = it }
        }
    }
}

/**
 * Plays [ConversationSounds] for the open conversation: "sent" when one of this account's messages finishes sending,
 * "received" when someone else's new message arrives. Messages already there when the conversation loaded, and
 * older history paged in later, stay silent.
 */
@androidx.compose.runtime.Composable
internal fun PlayConversationSounds(messages: List<MatrixMessage>, enabled: Boolean) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val baseline = androidx.compose.runtime.remember { java.util.concurrent.atomic.AtomicLong(-1L) }
    val counts = androidx.compose.runtime.remember { IntArray(2) { -1 } }
    androidx.compose.runtime.LaunchedEffect(messages) {
        if (messages.isEmpty()) return@LaunchedEffect
        if (baseline.get() < 0) baseline.set(messages.maxOf { it.timestampMillis })
        val since = baseline.get()
        val sent = messages.count { it.own && it.status == DeliveryStatus.Sent && it.call == null && it.timestampMillis > since }
        val received = messages.count { !it.own && it.call == null && !it.deleted && it.timestampMillis > since }
        if (enabled && counts[0] >= 0 && sent > counts[0]) ConversationSounds.play(context, ConversationSounds.Kind.Sent)
        if (enabled && counts[1] >= 0 && received > counts[1]) ConversationSounds.play(context, ConversationSounds.Kind.Received)
        counts[0] = sent
        counts[1] = received
    }
}

/**
 * Short in-conversation sounds for a message leaving (rising "pop") and one arriving (falling two-note chime), as
 * Messages plays them while a conversation is open. Synthesised once into static AudioTracks; silent while the
 * ringer is silent or on vibrate, like other sonification sounds.
 */
internal object ConversationSounds {
    enum class Kind { Sent, Received }

    private const val RATE = 44_100
    private val tracks = HashMap<Kind, AudioTrack>()
    /** Sounds played since start, by kind (read by tests). */
    internal val played = java.util.concurrent.ConcurrentHashMap<Kind, Int>()

    fun play(context: Context, kind: Kind) {
        val audio = context.getSystemService(AudioManager::class.java)
        if (audio != null && audio.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        runCatching {
            val track = synchronized(tracks) { tracks.getOrPut(kind) { build(kind) } }
            track.stop()
            track.reloadStaticData()
            track.play()
            played.merge(kind, 1) { old, added -> old + added }
        }.onFailure { android.util.Log.w("ConversationSounds", "Could not play $kind", it) }
    }

    private fun build(kind: Kind): AudioTrack {
        val samples = when (kind) {
            // 70 ms rising sweep.
            Kind.Sent -> tone(durationMs = 70) { t -> 660.0 + 660.0 * t }
            // Two short notes, high then lower.
            Kind.Received -> tone(durationMs = 80) { 1318.5 } + tone(durationMs = 110) { 987.8 }
        }
        val bytes = samples.size * 2
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(bytes)
            .build()
            .also { it.write(samples, 0, samples.size) }
    }

    /** A sine tone with a quick attack and exponential decay; [frequency] maps progress 0..1 to Hz. */
    private fun tone(durationMs: Int, frequency: (Double) -> Double): ShortArray {
        val count = RATE * durationMs / 1000
        var phase = 0.0
        return ShortArray(count) { index ->
            val t = index.toDouble() / count
            phase += 2 * PI * frequency(t) / RATE
            val envelope = minOf(1.0, index / (RATE * 0.004)) * exp(-4.0 * t)
            (sin(phase) * envelope * 0.35 * Short.MAX_VALUE).toInt().toShort()
        }
    }
}
