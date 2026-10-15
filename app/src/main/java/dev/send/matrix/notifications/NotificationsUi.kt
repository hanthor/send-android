package dev.send.matrix.notifications

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner

internal fun notificationsAllowed(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

internal fun distributorLabel(context: Context, packageName: String): String = try {
    val packageManager = context.packageManager
    packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
} catch (_: PackageManager.NameNotFoundException) {
    packageName
}

/** One line for the account card's Notifications row. */
internal fun notificationSummary(context: Context, allowed: Boolean, status: PushStatus): String = when {
    !allowed -> "Off"
    status.pushActive && status.distributor != null -> "Push via ${distributorLabel(context, status.distributor)}"
    status.distributor != null && status.error == null -> "Connecting to ${distributorLabel(context, status.distributor)}…"
    else -> "Background sync every 15 minutes"
}

private const val PERMISSION_PROMPTED = "permission_prompted"

/** Whether Send already explained notifications and asked for the permission once. */
internal fun notificationPermissionPrompted(context: Context): Boolean =
    NotificationRouter.prefs(context).getBoolean(PERMISSION_PROMPTED, false)

internal fun markNotificationPermissionPrompted(context: Context) {
    NotificationRouter.prefs(context).edit().putBoolean(PERMISSION_PROMPTED, true).apply()
}

/** Shown once after the first sign-in, before the system permission dialog, saying what notifications contain. */
@Composable
internal fun NotificationPermissionDialog(onTurnOn: () -> Unit, onNotNow: () -> Unit) {
    AlertDialog(
        onDismissRequest = onNotNow,
        icon = { Icon(Icons.Outlined.Notifications, contentDescription = null) },
        title = { Text("Get notified about new messages") },
        text = {
            Text(
                "Send can show new messages from your encrypted conversations, with Reply and Mark as read. " +
                    "Messages are decrypted on this device, and their text stays hidden on the lock screen.",
            )
        },
        confirmButton = { TextButton(onClick = onTurnOn) { Text("Turn on") } },
        dismissButton = { TextButton(onClick = onNotNow) { Text("Not now") } },
    )
}

/**
 * Notifications status from the account card: whether Android lets Send notify, how messages arrive (UnifiedPush
 * distributor or background sync), and the choice between installed distributors.
 */
@Composable
internal fun NotificationsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    // Re-read on resume: the user may come back from system settings or install a distributor.
    val allowed = remember(lifecycleState) { notificationsAllowed(context) }
    val fullScreenCalls = remember(lifecycleState) { IncomingCalls.canRingFullScreen(context) }
    val distributors = remember(lifecycleState) { PushRegistration.distributors(context) }
    LaunchedEffect(lifecycleState) { PushRegistration.refreshStatus(context) }
    val status by PushRegistration.status.collectAsState()
    val requestPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        PushRegistration.refreshStatus(context)
    }
    val delivery = when {
        status.pushActive && status.distributor != null ->
            "New messages arrive instantly through ${distributorLabel(context, status.distributor!!)} (UnifiedPush)."
        status.distributor != null && status.error == null ->
            "Connecting to ${distributorLabel(context, status.distributor!!)}… Until push is set up, Send checks for new messages about every 15 minutes."
        status.error != null -> "Push isn't working: ${status.error} Send checks for new messages about every 15 minutes instead."
        else -> "Send checks for new messages about every 15 minutes while it isn't open, and right away while it runs."
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Notifications, contentDescription = null) },
        title = { Text("Notifications") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!allowed) {
                    Text("Notifications are off for Send.", color = MaterialTheme.colorScheme.error)
                    TextButton(
                        onClick = {
                            val activity = context as? android.app.Activity
                            if (activity != null && (!notificationPermissionPrompted(context) || activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS))) {
                                markNotificationPermissionPrompted(context)
                                requestPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                context.startActivity(
                                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                                )
                            }
                        },
                    ) { Text("Turn on notifications") }
                }
                Text(delivery, style = MaterialTheme.typography.bodyMedium)
                if (allowed) {
                    // Android's per-app page: the Messages / Calls channels' sounds, pop-up and lock-screen settings.
                    TextButton(
                        onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                            )
                        },
                    ) { Text("Sounds, pop-ups and lock screen") }
                }
                if (allowed && !fullScreenCalls) {
                    // Android 14+ special access: without it a ring is only a heads-up notification.
                    Text(
                        "Incoming calls can't take over the screen, so they ring as a notification only.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(
                        onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${context.packageName}")),
                            )
                        },
                    ) { Text("Allow full-screen calls") }
                }
                Text("Deliver new messages with", style = MaterialTheme.typography.titleSmall)
                Column(Modifier.selectableGroup()) {
                    distributors.forEach { packageName ->
                        DeliveryOption(
                            label = distributorLabel(context, packageName),
                            selected = status.distributor == packageName,
                            onSelect = { PushRegistration.useDistributor(context, packageName) },
                        )
                    }
                    DeliveryOption(
                        label = "Background sync only",
                        selected = status.distributor == null || status.distributor !in distributors,
                        onSelect = { PushRegistration.useBackgroundSync(context) },
                    )
                }
                if (distributors.isEmpty()) {
                    Text(
                        "For instant notifications, install a UnifiedPush distributor such as ntfy, then choose it here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun DeliveryOption(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .selectable(selected = selected, onClick = { if (!selected) onSelect() }, role = Role.RadioButton)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, modifier = Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge)
    }
}
