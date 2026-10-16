package dev.send.matrix.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.service.notification.StatusBarNotification
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import androidx.core.content.LocusIdCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import dev.send.matrix.MainActivity
import dev.send.matrix.R
import dev.send.matrix.ShareTarget
import dev.send.matrix.avatarPalette

/**
 * Renders conversations as `MessagingStyle` notifications on the "Messages" channel, one per room (tagged with
 * the room ID), backed by a long-lived conversation shortcut so they sit in the system's Conversations section.
 * Message text is private on the lock screen.
 */
internal object MessageNotifier {
    const val CHANNEL_MESSAGES = "messages"
    const val NOTIFICATION_ID = 1
    const val EXTRA_ROOM_ID = "dev.send.matrix.extra.ROOM_ID"
    const val EXTRA_EVENT_ID = "dev.send.matrix.extra.EVENT_ID"
    const val REMOTE_INPUT_REPLY = "dev.send.matrix.REPLY_TEXT"
    const val ACTION_OPEN_CONVERSATION = "dev.send.matrix.action.OPEN_CONVERSATION"
    private const val EXTRA_LAST_EVENT_ID = "dev.send.matrix.extra.LAST_EVENT_ID"
    private const val ACCENT = 0xFF415F91.toInt()

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_MESSAGES) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "New messages in your conversations"
                lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
            },
        )
    }

    /** Opens [roomId] in Send; used by the notification tap and the conversation shortcut. */
    fun conversationIntent(context: Context, roomId: String): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(ACTION_OPEN_CONVERSATION)
            // Distinct data per room keeps one PendingIntent per conversation.
            .setData(roomUri(roomId))
            .putExtra(EXTRA_ROOM_ID, roomId)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)

    fun showMessage(context: Context, message: ParsedNotification.Message, ownUserId: String?) {
        val sender = person(message.senderId, message.senderName)
        val style = activeNotification(context, message.roomId)?.let { messagingStyle(it) }
            ?: NotificationCompat.MessagingStyle(Person.Builder().setName("You").setKey(ownUserId ?: "self").build())
        style.addMessage(NotificationCompat.MessagingStyle.Message(message.body, message.timestampMillis, sender))
        style.setConversationTitle(if (message.isGroup) message.roomName else null)
        style.setGroupConversation(message.isGroup)
        publishShortcut(context, message.roomId, message.roomName, if (message.isGroup) null else sender)
        post(context, message.roomId, style, lastEventId = message.eventId, alert = true, status = null)
    }

    /** Adds the user's sent inline reply to the conversation and stops the reply spinner, silently. */
    fun showSentReply(context: Context, roomId: String, text: String) {
        val active = activeNotification(context, roomId) ?: return
        val style = messagingStyle(active) ?: return
        style.addMessage(NotificationCompat.MessagingStyle.Message(text, System.currentTimeMillis(), null as Person?))
        post(context, roomId, style, lastEventId(active), alert = false, status = null)
    }

    /** Re-posts the conversation (ending the reply spinner) with why the action didn't go through. */
    fun showActionFailed(context: Context, roomId: String, status: String) {
        val active = activeNotification(context, roomId) ?: return
        val style = messagingStyle(active) ?: return
        post(context, roomId, style, lastEventId(active), alert = false, status = status)
    }

    fun cancel(context: Context, roomId: String) {
        NotificationManagerCompat.from(context).cancel(roomId, NOTIFICATION_ID)
    }

    /** Sign-out: removes every conversation notification and shortcut of the old session. */
    fun clearAll(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        manager.activeNotifications.filter { it.id == NOTIFICATION_ID && it.tag != null }.forEach { manager.cancel(it.tag, it.id) }
        val ids = ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_DYNAMIC).map { it.id }
        ShortcutManagerCompat.removeAllDynamicShortcuts(context)
        if (ids.isNotEmpty()) ShortcutManagerCompat.removeLongLivedShortcuts(context, ids)
    }

    private fun post(
        context: Context,
        roomId: String,
        style: NotificationCompat.MessagingStyle,
        lastEventId: String?,
        alert: Boolean,
        status: String?,
    ) {
        ensureChannel(context)
        val messageCount = style.messages.count { it.person != null && it.person?.key != style.user.key }
        val publicVersion = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_message)
            .setColor(ACCENT)
            .setContentTitle("Send")
            .setContentText(if (messageCount <= 1) "New message" else "$messageCount new messages")
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()
        val builder = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_message)
            .setColor(ACCENT)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setShortcutId(roomId)
            .setLocusId(LocusIdCompat(roomId))
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    0,
                    conversationIntent(context, roomId),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .setAutoCancel(true)
            .setOnlyAlertOnce(!alert)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setWhen(style.messages.lastOrNull()?.timestamp ?: System.currentTimeMillis())
            .setShowWhen(true)
            .addExtras(android.os.Bundle().apply { putString(EXTRA_LAST_EVENT_ID, lastEventId) })
            // Messages orders the actions "Mark as read", then "Reply".
            .addAction(markReadAction(context, roomId, lastEventId))
            .addAction(replyAction(context, roomId))
        if (status != null) builder.setSubText(status)
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        NotificationManagerCompat.from(context).notify(roomId, NOTIFICATION_ID, builder.build())
    }

    private fun replyAction(context: Context, roomId: String): NotificationCompat.Action {
        val intent = actionIntent(context, NotificationActionReceiver.ACTION_REPLY, roomId)
        // Mutable so the system can attach the RemoteInput text; the intent is explicit.
        val pending = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
        return NotificationCompat.Action.Builder(R.drawable.ic_action_reply, "Reply", pending)
            .addRemoteInput(RemoteInput.Builder(REMOTE_INPUT_REPLY).setLabel("Message").build())
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .setAllowGeneratedReplies(true)
            .build()
    }

    private fun markReadAction(context: Context, roomId: String, lastEventId: String?): NotificationCompat.Action {
        val intent = actionIntent(context, NotificationActionReceiver.ACTION_MARK_READ, roomId).putExtra(EXTRA_EVENT_ID, lastEventId)
        val pending = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Action.Builder(R.drawable.ic_action_mark_read, "Mark as read", pending)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .build()
    }

    private fun actionIntent(context: Context, action: String, roomId: String): Intent =
        Intent(context, NotificationActionReceiver::class.java)
            .setAction(action)
            .setData(roomUri(roomId))
            .putExtra(EXTRA_ROOM_ID, roomId)

    /**
     * Pushes (or refreshes and ranks up) [roomId]'s long-lived conversation shortcut when the user sends there, so
     * recent conversations show in the system share sheet; [dmUserId] gives a DM its person like a notification does.
     */
    fun publishConversationShortcut(context: Context, roomId: String, roomName: String, dmUserId: String?) {
        publishShortcut(context, roomId, roomName, dmUserId?.let { person(it, roomName) })
    }

    private fun publishShortcut(context: Context, roomId: String, roomName: String, person: Person?) {
        val shortcut = ShortcutInfoCompat.Builder(context, roomId)
            .setShortLabel(roomName)
            .setLongLabel(roomName)
            .setIcon(IconCompat.createWithAdaptiveBitmap(avatarBitmap(roomName, adaptive = true)))
            .setIntent(conversationIntent(context, roomId))
            .setLongLived(true)
            .setLocusId(LocusIdCompat(roomId))
            // Conversation shortcut, and a direct-share target (res/xml/shortcuts.xml) for the same room.
            .setCategories(setOf(ShortcutInfo.SHORTCUT_CATEGORY_CONVERSATION, ShareTarget.CATEGORY_SHARE_TARGET))
            .setIsConversation()
            .apply { if (person != null) setPerson(person) }
            .build()
        ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
    }

    private fun person(userId: String, name: String): Person =
        Person.Builder()
            .setKey(userId)
            .setName(name)
            .setIcon(IconCompat.createWithBitmap(avatarBitmap(name, adaptive = false)))
            .build()

    /**
     * Send's conversation-list avatar as a bitmap: the same palette colour for [name] with its white initial —
     * a circle for people in the notification, a full-bleed square for the launcher-masked shortcut icon.
     */
    internal fun avatarBitmap(name: String, adaptive: Boolean): Bitmap {
        val size = if (adaptive) 216 else 128
        // Adaptive icons keep their content inside the middle 72 of 108 units.
        val glyphBox = if (adaptive) size * 72f / 108f else size.toFloat()
        val (background, foreground) = avatarPalette(name)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = background.toArgb() }
        if (adaptive) canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
        else canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        val initial = name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: return bitmap
        paint.apply {
            color = foreground.toArgb()
            textSize = glyphBox * 0.45f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val baseline = size / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(initial, size / 2f, baseline, paint)
        return bitmap
    }

    private fun activeNotification(context: Context, roomId: String): StatusBarNotification? =
        NotificationManagerCompat.from(context).activeNotifications.firstOrNull { it.id == NOTIFICATION_ID && it.tag == roomId }

    private fun messagingStyle(active: StatusBarNotification): NotificationCompat.MessagingStyle? =
        NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(active.notification)

    private fun lastEventId(active: StatusBarNotification): String? = active.notification.extras.getString(EXTRA_LAST_EVENT_ID)

    private fun roomUri(roomId: String): Uri = Uri.Builder().scheme("dev.send.matrix").authority("conversation").appendPath(roomId).build()
}
