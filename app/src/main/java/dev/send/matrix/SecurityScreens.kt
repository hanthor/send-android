package dev.send.matrix

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.GppMaybe
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.matrix.rustcomponents.sdk.BackupState
import org.matrix.rustcomponents.sdk.RecoveryState
import org.matrix.rustcomponents.sdk.VerificationState

internal const val RECOVERY_KEY_TAG = "recoveryKey"
internal const val RECOVERY_KEY_INPUT_TAG = "recoveryKeyInput"
internal const val DECRYPTION_ACTION_TAG = "decryptionAction"

/** One-line state for the account menu's Security row. */
internal fun securitySummary(verification: VerificationState, recovery: RecoveryState): String {
    val device = when (verification) {
        VerificationState.VERIFIED -> "Device verified"
        VerificationState.UNVERIFIED -> "Device not verified"
        VerificationState.UNKNOWN -> "Checking device"
    }
    val backup = when (recovery) {
        RecoveryState.ENABLED -> "recovery on"
        RecoveryState.DISABLED -> "recovery off"
        RecoveryState.INCOMPLETE -> "recovery key needed"
        RecoveryState.UNKNOWN -> "checking recovery"
    }
    return "$device · $backup"
}

/** Account menu opened from the home top bar's account button, laid out like Messages' account card. */
@Composable
internal fun AccountSheet(
    userId: String,
    securitySummary: String,
    onSecurity: () -> Unit,
    onSignOut: () -> Unit,
    onDismiss: () -> Unit,
    /** Opens Settings (Messages: account menu → Messages settings). */
    onSettings: () -> Unit,
    /** Display name and avatar shown on the account card. */
    profile: OwnProfile? = null,
    /** Opens the profile page from the account card; null leaves the card inert. */
    onProfile: (() -> Unit)? = null,
    /** Opens the Archived conversations screen (Messages: account menu → Archived). */
    onArchived: (() -> Unit)? = null,
    /** Opens Blocked contacts. */
    onBlocked: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.padding(horizontal = 16.dp).widthIn(max = 480.dp).fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            color = colors.surfaceContainerHigh,
        ) {
            Column(Modifier.padding(8.dp)) {
                Box(Modifier.fillMaxWidth().height(48.dp)) {
                    IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterStart)) {
                        Icon(Icons.Filled.Close, contentDescription = "Close")
                    }
                    Text("Send", style = MaterialTheme.typography.titleMedium, modifier = Modifier.align(Alignment.Center))
                }
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = colors.surface,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
                        .then(if (onProfile != null) Modifier.clickable(onClickLabel = "Profile", onClick = onProfile) else Modifier),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        val name = profile?.displayName?.takeIf { it.isNotBlank() }
                        Avatar(name = name ?: userId.removePrefix("@").substringBefore(':'), size = 40.dp, url = profile?.avatarUrl)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(name ?: userId, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                if (name != null) userId else "Matrix account",
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (onArchived != null) AccountMenuRow(Icons.Outlined.Archive, "Archived", null, onArchived)
                if (onBlocked != null) AccountMenuRow(Icons.Outlined.Block, "Blocked contacts", null, onBlocked)
                AccountMenuRow(Icons.Outlined.Security, "Security", securitySummary, onSecurity)
                AccountMenuRow(Icons.Outlined.Settings, "Settings", null, onSettings)
                AccountMenuRow(Icons.AutoMirrored.Outlined.Logout, "Sign out", null, onSignOut)
            }
        }
    }
}

@Composable
private fun AccountMenuRow(icon: ImageVector, label: String, subtitle: String?, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(20.dp))
        Column {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Confirms sign-out, warning when this device's keys aren't covered by recovery. */
@Composable
internal fun SignOutDialog(
    recoveryEnabled: Boolean,
    busy: Boolean,
    error: String,
    onSetUpRecovery: () -> Unit,
    onSignOut: () -> Unit,
    onSignOutLocally: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        icon = { Icon(Icons.AutoMirrored.Outlined.Logout, contentDescription = null) },
        title = { Text(if (recoveryEnabled) "Sign out?" else "Sign out without recovery?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    if (recoveryEnabled) {
                        "Your message keys are backed up. To read your history after signing in again, enter your recovery key."
                    } else {
                        "Recovery isn't set up on this device. If you sign out, you'll lose access to encrypted messages only this device can read."
                    },
                )
                if (busy) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Signing out…")
                    }
                }
                if (error.isNotBlank()) {
                    Text(
                        "Your homeserver couldn't sign this session out: $error",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            if (error.isNotBlank()) {
                TextButton(onClick = onSignOutLocally, enabled = !busy) { Text("Sign out on this device only") }
            } else {
                TextButton(onClick = onSignOut, enabled = !busy) { Text(if (recoveryEnabled) "Sign out" else "Sign out anyway") }
            }
        },
        dismissButton = {
            Row {
                if (!recoveryEnabled) {
                    TextButton(onClick = onSetUpRecovery, enabled = !busy) { Text("Set up recovery") }
                }
                TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") }
            }
        },
    )
}

/** Messages-style banner at the top of the conversation list while this session is unverified. */
@Composable
internal fun VerifyDeviceBanner(onVerify: () -> Unit, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(24.dp),
        color = colors.secondaryContainer,
        contentColor = colors.onSecondaryContainer,
    ) {
        Column(Modifier.padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 4.dp)) {
            Row {
                Icon(Icons.Outlined.GppMaybe, contentDescription = null)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("Verify this device", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Confirm it's you from another session or with your recovery key to read your encrypted messages.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Row(Modifier.align(Alignment.End)) {
                TextButton(onClick = onDismiss) { Text("Not now") }
                TextButton(onClick = onVerify) { Text("Verify") }
            }
        }
    }
}

/** Muted placeholder for an event this device can't decrypt yet; the SDK replaces it once the key arrives. */
@Composable
internal fun UndecryptableBubble(
    failure: DecryptionFailure,
    isFromMe: Boolean,
    shape: Shape,
    isLastInGroup: Boolean,
    actionLabel: String?,
    onAction: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = if (isLastInGroup) 4.dp else 1.dp),
        contentAlignment = if (isFromMe) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Surface(
            shape = shape,
            color = colors.surfaceContainerLow,
            contentColor = colors.onSurfaceVariant,
            border = BorderStroke(1.dp, colors.surfaceContainerHighest),
            modifier = Modifier.widthIn(max = 280.dp),
        ) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = if (actionLabel != null) 2.dp else 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Waiting for this message…", style = MaterialTheme.typography.bodyLarge, fontStyle = FontStyle.Italic)
                }
                Text(failure.explanation, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                if (actionLabel != null) {
                    TextButton(
                        onClick = onAction,
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
                        modifier = Modifier.testTag(DECRYPTION_ACTION_TAG),
                    ) {
                        Text(actionLabel)
                    }
                }
            }
        }
    }
}

internal val DecryptionFailure.explanation: String
    get() = when (this) {
        DecryptionFailure.MissingKey -> "The key for this encrypted message hasn't reached this device yet."
        DecryptionFailure.SentBeforeJoined -> "This message was sent before you joined the conversation."
        DecryptionFailure.UnverifiedDevice -> "Verify this device to read messages sent before you signed in."
        DecryptionFailure.BackupDisabled -> "Messages sent before you signed in here are restored with your recovery key."
        DecryptionFailure.WithheldForUnverified -> "The sender only shares messages with verified devices."
        DecryptionFailure.WithheldBySender -> "The sender didn't share this message with you."
        DecryptionFailure.SenderIdentityChanged -> "The sender's verified identity changed, so this message was blocked."
        DecryptionFailure.InsecureSenderDevice -> "This was sent from a device its owner hasn't verified, so it was blocked."
    }

/** Whether verifying this device or entering the recovery key can make the message readable. */
internal val DecryptionFailure.fixableOnThisDevice: Boolean
    get() = when (this) {
        DecryptionFailure.MissingKey,
        DecryptionFailure.UnverifiedDevice,
        DecryptionFailure.BackupDisabled,
        DecryptionFailure.WithheldForUnverified -> true
        DecryptionFailure.SentBeforeJoined,
        DecryptionFailure.WithheldBySender,
        DecryptionFailure.SenderIdentityChanged,
        DecryptionFailure.InsecureSenderDevice -> false
    }

private enum class SecurityPage { Overview, SetUp, EnterKey }

/** Device verification and recovery (key backup) for the signed-in account. */
@Composable
internal fun SecurityScreen(
    account: AccountInfo?,
    verification: VerificationState,
    recovery: RecoveryState,
    backup: BackupState,
    hasOtherDevices: Boolean,
    onBack: () -> Unit,
    onVerifyWithDevice: () -> Unit,
    enableRecovery: suspend (onProgress: (String) -> Unit) -> String,
    recover: suspend (String) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf(SecurityPage.Overview) }
    var working by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var failure by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    // Held only in composition: Send never stores the recovery key.
    var recoveryKey by remember { mutableStateOf<String?>(null) }
    // Leaving mid-operation would cancel the SDK call; leaving before the key is saved would lose it.
    val locked = working || recoveryKey != null
    fun back() {
        when {
            locked -> Unit
            page != SecurityPage.Overview -> {
                page = SecurityPage.Overview
                failure = ""
            }
            else -> onBack()
        }
    }
    fun setUpRecovery() {
        page = SecurityPage.SetUp
        failure = ""
        notice = ""
        progress = "Starting…"
        working = true
        scope.launch {
            try {
                recoveryKey = enableRecovery { progress = it }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failure = error.message ?: "Recovery couldn't be set up."
            } finally {
                working = false
            }
        }
    }
    fun submitRecoveryKey(key: String) {
        failure = ""
        working = true
        scope.launch {
            try {
                recover(key)
                page = SecurityPage.Overview
                notice = "Recovery key accepted. Your message history is being restored."
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failure = error.message?.let { "That recovery key didn't work: $it" } ?: "That recovery key didn't work."
            } finally {
                working = false
            }
        }
    }
    BackHandler { back() }
    Scaffold(
        containerColor = colors.surfaceContainer,
        topBar = {
            Surface(color = colors.surfaceContainer, modifier = Modifier.statusBarsPadding()) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = ::back, enabled = !locked) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                    Text(
                        if (page == SecurityPage.Overview) "Security" else "Recovery",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
    ) { innerPadding ->
        Surface(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = colors.surface,
        ) {
            AnimatedContent(
                targetState = page,
                transitionSpec = { if (targetState.ordinal > initialState.ordinal) SendMotion.open else SendMotion.close },
                label = "SecurityPage",
            ) { current ->
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().imePadding().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    when (current) {
                        SecurityPage.Overview -> SecurityOverview(
                            account = account,
                            verification = verification,
                            recovery = recovery,
                            backup = backup,
                            hasOtherDevices = hasOtherDevices,
                            notice = notice,
                            onVerifyWithDevice = onVerifyWithDevice,
                            onEnterKey = {
                                failure = ""
                                notice = ""
                                page = SecurityPage.EnterKey
                            },
                            onSetUp = ::setUpRecovery,
                        )
                        SecurityPage.SetUp -> RecoverySetUp(
                            working = working,
                            progress = progress,
                            failure = failure,
                            recoveryKey = recoveryKey,
                            onRetry = ::setUpRecovery,
                            onDone = {
                                recoveryKey = null
                                page = SecurityPage.Overview
                            },
                        )
                        SecurityPage.EnterKey -> RecoveryKeyEntry(
                            working = working,
                            failure = failure,
                            onSubmit = ::submitRecoveryKey,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SecurityOverview(
    account: AccountInfo?,
    verification: VerificationState,
    recovery: RecoveryState,
    backup: BackupState,
    hasOtherDevices: Boolean,
    notice: String,
    onVerifyWithDevice: () -> Unit,
    onEnterKey: () -> Unit,
    onSetUp: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val recoveryExists = recovery == RecoveryState.ENABLED || recovery == RecoveryState.INCOMPLETE
    if (notice.isNotBlank()) Text(notice, style = MaterialTheme.typography.bodyMedium, color = colors.primary)
    SecurityCard(
        icon = when (verification) {
            VerificationState.VERIFIED -> Icons.Outlined.VerifiedUser
            VerificationState.UNVERIFIED -> Icons.Outlined.GppMaybe
            VerificationState.UNKNOWN -> Icons.Outlined.Devices
        },
        iconTint = if (verification == VerificationState.UNVERIFIED) colors.error else colors.primary,
        title = "This device",
        status = when (verification) {
            VerificationState.VERIFIED -> "Verified"
            VerificationState.UNVERIFIED -> "Not verified"
            VerificationState.UNKNOWN -> "Checking…"
        },
    ) {
        if (account != null) {
            Text("Session ${account.deviceId}", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        when (verification) {
            VerificationState.VERIFIED -> Text("This device can read your encrypted messages, and your contacts can trust it.")
            VerificationState.UNVERIFIED -> {
                Text("Verify this device to read your encrypted history and so your contacts can trust it.")
                if (hasOtherDevices) Button(onClick = onVerifyWithDevice) { Text("Verify this device") }
                if (recoveryExists) OutlinedButton(onClick = onEnterKey) { Text("Verify with recovery key") }
                if (!hasOtherDevices && !recoveryExists) {
                    Text(
                        "No other verified session or recovery key is available. Sign in on a verified device first.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
            VerificationState.UNKNOWN -> Text("Checking this device's encryption status…")
        }
    }
    SecurityCard(
        icon = Icons.Outlined.Key,
        iconTint = colors.primary,
        title = "Recovery",
        status = when (recovery) {
            RecoveryState.ENABLED -> "On"
            RecoveryState.DISABLED -> "Off"
            RecoveryState.INCOMPLETE -> "Needs your recovery key"
            RecoveryState.UNKNOWN -> "Checking…"
        },
    ) {
        Text(
            when (recovery) {
                RecoveryState.ENABLED -> "Your message keys are backed up to your homeserver, locked with your recovery key. Use it to restore your history on a new device."
                RecoveryState.DISABLED -> "Set up recovery so you don't lose your encrypted messages if you lose or sign out of your devices."
                RecoveryState.INCOMPLETE -> "Recovery is set up for your account, but this device hasn't been unlocked with your recovery key yet."
                RecoveryState.UNKNOWN -> "Checking recovery…"
            },
        )
        Text("Key backup: ${backupLabel(backup)}", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        when (recovery) {
            RecoveryState.DISABLED ->
                // Secret storage must hold this account's cross-signing keys, which only a verified device has.
                if (verification == VerificationState.VERIFIED) {
                    Button(onClick = onSetUp) { Text("Set up recovery") }
                } else {
                    Text("Verify this device first to set up recovery.", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            RecoveryState.INCOMPLETE -> Button(onClick = onEnterKey) { Text("Enter recovery key") }
            RecoveryState.ENABLED, RecoveryState.UNKNOWN -> Unit
        }
    }
}

private fun backupLabel(state: BackupState): String = when (state) {
    BackupState.ENABLED -> "On"
    BackupState.CREATING, BackupState.ENABLING, BackupState.RESUMING -> "Turning on…"
    BackupState.DOWNLOADING -> "Restoring keys…"
    BackupState.DISABLING -> "Turning off…"
    BackupState.UNKNOWN -> "Off on this device"
}

@Composable
private fun SecurityCard(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    status: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(24.dp), color = colors.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).background(colors.secondaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(status, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
            }
            content()
        }
    }
}

@Composable
private fun ColumnScope.RecoverySetUp(
    working: Boolean,
    progress: String,
    failure: String,
    recoveryKey: String?,
    onRetry: () -> Unit,
    onDone: () -> Unit,
) {
    when {
        recoveryKey != null -> RecoveryKeyReveal(recoveryKey, onDone)
        working -> {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 24.dp))
            Text("Setting up recovery", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.align(Alignment.CenterHorizontally))
            Text(progress, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterHorizontally))
        }
        else -> {
            Text("Recovery couldn't be set up", style = MaterialTheme.typography.headlineSmall)
            Text(failure, color = MaterialTheme.colorScheme.error)
            Button(onClick = onRetry) { Text("Try again") }
        }
    }
}

@Composable
private fun ColumnScope.RecoveryKeyReveal(recoveryKey: String, onDone: () -> Unit) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    Text("Save your recovery key", style = MaterialTheme.typography.headlineSmall)
    Text(
        "You'll need it to read your encrypted messages on a new device. Keep it somewhere safe, like a password manager. Send won't show it again.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        SelectionContainer {
            Text(
                recoveryKey,
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(16.dp).testTag(RECOVERY_KEY_TAG),
            )
        }
    }
    OutlinedButton(onClick = { copyRecoveryKey(context, recoveryKey); copied = true }) {
        Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(if (copied) "Copied" else "Copy")
    }
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .toggleable(value = saved, role = Role.Checkbox, onValueChange = { saved = it })
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = saved, onCheckedChange = null)
        Spacer(Modifier.width(8.dp))
        Text("I've saved my recovery key")
    }
    Button(onClick = onDone, enabled = saved, modifier = Modifier.fillMaxWidth()) { Text("Done") }
}

private fun copyRecoveryKey(context: Context, recoveryKey: String) {
    val clip = ClipData.newPlainText("Recovery key", recoveryKey)
    // Marks the clip sensitive so Android hides it from the clipboard preview.
    clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
}

@Composable
private fun RecoveryKeyEntry(working: Boolean, failure: String, onSubmit: (String) -> Unit) {
    var key by remember { mutableStateOf("") }
    val canSubmit = key.isNotBlank() && !working
    Text("Enter your recovery key", style = MaterialTheme.typography.headlineSmall)
    Text(
        "Use the recovery key you saved when you set up recovery. It verifies this device and restores your encrypted message history.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = key,
        onValueChange = { key = it },
        label = { Text("Recovery key") },
        enabled = !working,
        minLines = 2,
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (canSubmit) onSubmit(key) }),
        modifier = Modifier.fillMaxWidth().testTag(RECOVERY_KEY_INPUT_TAG),
    )
    if (failure.isNotBlank()) Text(failure, color = MaterialTheme.colorScheme.error)
    Button(onClick = { onSubmit(key) }, enabled = canSubmit, modifier = Modifier.fillMaxWidth()) {
        if (working) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            Text("Continue")
        }
    }
}

/** Prompt for a verification request another session of this account sent to this device. */
@Composable
internal fun VerificationRequestDialog(request: VerificationFlow.Incoming, onAccept: () -> Unit, onDecline: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDecline,
        properties = DialogProperties(dismissOnClickOutside = false),
        icon = { Icon(Icons.Outlined.Devices, contentDescription = null) },
        title = { Text("Verification request") },
        text = {
            Text(
                "${request.deviceName ?: "Another session"} (${request.deviceId}) wants to verify with this device. " +
                    "Accept only if you just started verification there.",
            )
        },
        confirmButton = { TextButton(onClick = onAccept) { Text("Accept") } },
        dismissButton = { TextButton(onClick = onDecline) { Text("Decline") } },
    )
}

/** Full-screen SAS verification: waiting states, the 7 emoji (or numbers) to compare, and the outcome. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VerificationScreen(
    flow: VerificationFlow,
    onMatch: () -> Unit,
    onMismatch: () -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val finished = flow is VerificationFlow.Verified || flow is VerificationFlow.Cancelled || flow is VerificationFlow.Failed
    BackHandler { if (finished) onDone() else onCancel() }
    Scaffold(
        containerColor = colors.surfaceContainer,
        topBar = {
            Surface(color = colors.surfaceContainer, modifier = Modifier.statusBarsPadding()) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = if (finished) onDone else onCancel) {
                        Icon(Icons.Filled.Close, contentDescription = if (finished) "Close" else "Cancel verification")
                    }
                    Text("Verify device", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                }
            }
        },
    ) { innerPadding ->
        Surface(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = colors.surface,
        ) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when (flow) {
                    VerificationFlow.Requesting -> VerificationStatus(
                        icon = Icons.Outlined.Devices,
                        title = "Accept on your other device",
                        body = "Open Send or another Matrix app where you're signed in, and accept the request to verify this device.",
                        waiting = true,
                    )
                    VerificationFlow.Accepted, VerificationFlow.Starting -> VerificationStatus(
                        icon = Icons.Outlined.Devices,
                        title = "Waiting for your other device…",
                        body = "Keep both devices nearby. The emoji will appear here in a moment.",
                        waiting = true,
                    )
                    is VerificationFlow.Emojis -> {
                        Text("Compare emoji", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "Make sure the emoji below appear in the same order on your other device.",
                            color = colors.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            maxItemsInEachRow = 4,
                        ) {
                            flow.emojis.forEach { emoji ->
                                Column(Modifier.width(72.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(emoji.symbol, fontSize = 40.sp)
                                    Text(
                                        emoji.description,
                                        style = MaterialTheme.typography.labelMedium,
                                        textAlign = TextAlign.Center,
                                        maxLines = 2,
                                    )
                                }
                            }
                        }
                        MatchButtons(onMatch, onMismatch)
                    }
                    is VerificationFlow.Decimals -> {
                        Text("Compare numbers", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "Make sure these numbers appear in the same order on your other device.",
                            color = colors.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        Text(flow.values.joinToString("   "), style = MaterialTheme.typography.displaySmall)
                        MatchButtons(onMatch, onMismatch)
                    }
                    VerificationFlow.Confirming -> VerificationStatus(
                        icon = Icons.Outlined.Devices,
                        title = "Waiting for your other device to confirm…",
                        body = "Confirm that the emoji match on your other device too.",
                        waiting = true,
                    )
                    VerificationFlow.Verified -> {
                        VerificationStatus(
                            icon = Icons.Outlined.CheckCircle,
                            title = "Device verified",
                            body = "Your devices now trust each other and can share the keys to your encrypted messages.",
                            waiting = false,
                        )
                        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") }
                    }
                    is VerificationFlow.Cancelled -> {
                        VerificationStatus(
                            icon = Icons.Outlined.ErrorOutline,
                            title = "Verification cancelled",
                            body = flow.reason ?: "Verification was cancelled on one of the devices.",
                            waiting = false,
                        )
                        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Close") }
                    }
                    is VerificationFlow.Failed -> {
                        VerificationStatus(
                            icon = Icons.Outlined.ErrorOutline,
                            title = "Verification failed",
                            body = flow.reason ?: "Something went wrong. Try again from Security.",
                            waiting = false,
                        )
                        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Close") }
                    }
                    // Shown only while the screen animates out (or before an incoming request is accepted).
                    VerificationFlow.Idle, is VerificationFlow.Incoming -> Unit
                }
            }
        }
    }
}

@Composable
private fun VerificationStatus(icon: ImageVector, title: String, body: String, waiting: Boolean) {
    Box(
        Modifier.padding(top = 24.dp).size(72.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(36.dp))
    }
    Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
    Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    if (waiting) CircularProgressIndicator()
}

@Composable
private fun MatchButtons(onMatch: () -> Unit, onMismatch: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = onMismatch, modifier = Modifier.weight(1f)) { Text("They don't match") }
        Button(onClick = onMatch, modifier = Modifier.weight(1f)) { Text("They match") }
    }
}
