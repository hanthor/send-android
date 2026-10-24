package dev.send.matrix

import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal const val PROFILE_NAME_TAG = "profileName"
internal const val DEVICE_PASSWORD_TAG = "devicePassword"

/** Rows of one Messages-style segmented group, collected by [SettingsGroup]. */
internal class SettingsGroupScope {
    internal val rows = mutableListOf<@Composable () -> Unit>()

    fun row(content: @Composable () -> Unit) {
        rows += content
    }
}

/**
 * Messages' settings list: rows on separate surface tiles 2dp apart, the group's outer corners rounded large and
 * the inner ones small, on the surface-container background.
 */
@Composable
internal fun SettingsGroup(modifier: Modifier = Modifier, build: SettingsGroupScope.() -> Unit) {
    val rows = SettingsGroupScope().apply(build).rows
    if (rows.isEmpty()) return
    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        rows.forEachIndexed { index, row ->
            val top = if (index == 0) 20.dp else 4.dp
            val bottom = if (index == rows.lastIndex) 20.dp else 4.dp
            Surface(
                shape = RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth(),
            ) { row() }
        }
    }
}

/** A settings row: optional leading icon, title, optional summary, optional trailing content. */
@Composable
internal fun SettingsRow(
    title: String,
    summary: String? = null,
    icon: ImageVector? = null,
    titleColor: Color = Color.Unspecified,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = if (titleColor != Color.Unspecified) titleColor else MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (titleColor != Color.Unspecified) titleColor else MaterialTheme.colorScheme.onSurface)
            if (summary != null) {
                Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(16.dp))
            trailing()
        }
    }
}

/** A settings row with a switch; the whole row toggles it. */
@Composable
internal fun SettingsSwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, summary: String? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (summary != null) Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** Secondary screen frame: back arrow + title on the surface-container background, scrolling body. */
@Composable
internal fun SettingsPage(
    title: String,
    onBack: () -> Unit,
    snackbar: SnackbarHostState? = null,
    content: @Composable () -> Unit,
) {
    val leaving = rememberPredictiveBack(onBack = onBack)
    Scaffold(
        modifier = Modifier.predictiveBack(leaving),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = { ListScreenTopBar(title, onBack) },
        snackbarHost = { if (snackbar != null) SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) { content() }
    }
}

/** Account menu → Settings, laid out like Messages settings. */
private fun swipeSummary(actions: SwipeActions): String =
    if (actions.start == actions.end) actions.start.label else "Right: ${actions.start.label} · Left: ${actions.end.label}"

/** Messages' Settings → Swipe actions: a choice for each direction. */
@Composable
internal fun SwipeActionsPage(actions: SwipeActions, onChange: (SwipeActions) -> Unit, onBack: () -> Unit) {
    SettingsPage("Swipe actions", onBack) {
        Text(
            "Swipe right",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 28.dp, top = 12.dp, bottom = 4.dp),
        )
        SettingsGroup {
            SwipeAction.entries.forEach { choice ->
                row { SettingsRadioRow(choice.label, selected = actions.start == choice) { onChange(actions.copy(start = choice)) } }
            }
        }
        Text(
            "Swipe left",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 28.dp, top = 12.dp, bottom = 4.dp),
        )
        SettingsGroup {
            SwipeAction.entries.forEach { choice ->
                row { SettingsRadioRow(choice.label, selected = actions.end == choice) { onChange(actions.copy(end = choice)) } }
            }
        }
    }
}

@Composable
private fun SettingsRadioRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
internal fun SettingsScreen(
    notificationSummary: String,
    onNotifications: () -> Unit,
    sounds: Boolean,
    onSounds: (Boolean) -> Unit,
    theme: ThemeChoice,
    onTheme: (ThemeChoice) -> Unit,
    linkPreviews: Boolean,
    onLinkPreviews: (Boolean) -> Unit,
    swipeActions: SwipeActions = SwipeActions(),
    onSwipeActions: (SwipeActions) -> Unit = {},
    securitySummary: String,
    onSecurity: () -> Unit,
    onDevices: () -> Unit,
    onBlocked: () -> Unit,
    onArchived: () -> Unit,
    onAbout: () -> Unit,
    onBack: () -> Unit,
) {
    var choosingTheme by rememberSaveable { mutableStateOf(false) }
    var choosingPreviews by rememberSaveable { mutableStateOf(false) }
    var choosingSwipes by rememberSaveable { mutableStateOf(false) }
    if (choosingSwipes) {
        SwipeActionsPage(swipeActions, onSwipeActions, onBack = { choosingSwipes = false })
        return
    }
    if (choosingPreviews) {
        // Messages: Automatic previews is its own page of switches.
        SettingsPage("Automatic previews", onBack = { choosingPreviews = false }) {
            SettingsGroup(Modifier.padding(top = 4.dp)) {
                row { SettingsSwitchRow("Show web link previews", linkPreviews, onLinkPreviews) }
            }
            Text(
                "Previews are fetched by your homeserver, which then learns the links in your messages.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 28.dp),
            )
        }
        return
    }
    SettingsPage("Settings", onBack) {
        SettingsGroup(Modifier.padding(top = 4.dp)) {
            row { SettingsRow("Notifications", notificationSummary, onClick = onNotifications) }
            row { SettingsSwitchRow("Hear outgoing and incoming message sounds", sounds, onSounds) }
            row { SettingsRow("Choose theme", theme.label, onClick = { choosingTheme = true }) }
            row { SettingsRow("Automatic previews", previewsLabel(linkPreviews), onClick = { choosingPreviews = true }) }
            row { SettingsRow("Swipe actions", swipeSummary(swipeActions), onClick = { choosingSwipes = true }) }
        }
        SettingsGroup {
            row { SettingsRow("Security", securitySummary, onClick = onSecurity) }
            row { SettingsRow("Devices", "Sessions signed in to your account", onClick = onDevices) }
            row { SettingsRow("Spam & blocked", onClick = onBlocked) }
            row { SettingsRow("Archived", onClick = onArchived) }
        }
        SettingsGroup {
            row { SettingsRow("About & licenses", "Send · AGPL-3.0", onClick = onAbout) }
        }
    }
    if (choosingTheme) {
        ChoiceDialog(
            title = "Choose theme",
            options = ThemeChoice.entries.map { it.label },
            selected = theme.ordinal,
            onSelect = { index -> choosingTheme = false; onTheme(ThemeChoice.entries[index]) },
            onDismiss = { choosingTheme = false },
        )
    }
}

private fun previewsLabel(on: Boolean) = if (on) "Show web link previews" else "Don't show previews"

/** A single-choice dialog: picking an option applies it and closes. */
@Composable
internal fun ChoiceDialog(
    title: String,
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
    footnote: String? = null,
    summaries: List<String?> = emptyList(),
) {
    // Messages: the radio moves on tap; OK applies it, Cancel leaves things as they were.
    var picked by remember(selected) { mutableIntStateOf(selected) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(Modifier.selectableGroup()) {
                options.forEachIndexed { index, option ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .selectable(selected = index == picked, role = Role.RadioButton, onClick = { picked = index })
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = index == picked, onClick = null)
                        Column(Modifier.padding(start = 16.dp)) {
                            Text(option, style = MaterialTheme.typography.bodyLarge)
                            summaries.getOrNull(index)?.let {
                                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                if (footnote != null) {
                    Text(
                        footnote,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { if (picked == selected) onDismiss() else onSelect(picked) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Runs [action] in [scope] with busy/error state, reporting the failure message. */
private fun launchGuarded(
    scope: kotlinx.coroutines.CoroutineScope,
    setBusy: (Boolean) -> Unit,
    setError: (String) -> Unit,
    failure: String,
    action: suspend () -> Unit,
) {
    scope.launch {
        setBusy(true)
        setError("")
        try {
            action()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (problem: Exception) {
            setError(problem.message ?: failure)
        } finally {
            setBusy(false)
        }
    }
}

/** Account card → profile: display name and photo others see, and the Matrix ID with copy. */
@Composable
internal fun ProfileScreen(
    userId: String,
    profile: OwnProfile?,
    setName: suspend (String) -> Unit,
    setAvatar: suspend (Uri) -> Unit,
    removeAvatar: suspend () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val savedName = profile?.displayName.orEmpty()
    var name by rememberSaveable(savedName) { mutableStateOf(savedName) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            launchGuarded(scope, { busy = it }, { error = it }, "Your photo couldn't be changed.") {
                setAvatar(uri)
                scope.launch { snackbar.showSnackbar("Profile photo updated") }
            }
        }
    }
    val shownName = savedName.ifBlank { userId.removePrefix("@").substringBefore(':') }
    SettingsPage("Profile", onBack, snackbar) {
        Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box {
                Avatar(name = shownName, size = 120.dp, url = profile?.avatarUrl)
                FilledTonalIconButton(
                    onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    enabled = !busy,
                    modifier = Modifier.align(Alignment.BottomEnd).offset(x = 4.dp, y = 4.dp),
                ) { Icon(Icons.Outlined.CameraAlt, contentDescription = "Change profile photo") }
            }
            if (profile?.avatarUrl != null) {
                TextButton(
                    onClick = {
                        launchGuarded(scope, { busy = it }, { error = it }, "Your photo couldn't be removed.") {
                            removeAvatar()
                            scope.launch { snackbar.showSnackbar("Profile photo removed") }
                        }
                    },
                    enabled = !busy,
                ) { Text("Remove photo") }
            } else {
                Spacer(Modifier.height(12.dp))
            }
            Text(shownName, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp))
        }
        SettingsGroup {
            row {
                Column(Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Name") },
                        singleLine = true,
                        enabled = !busy,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth().testTag(PROFILE_NAME_TAG),
                    )
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "People you chat with see this name and photo.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Button(
                            onClick = {
                                launchGuarded(scope, { busy = it }, { error = it }, "Your name couldn't be changed.") {
                                    setName(name)
                                    scope.launch { snackbar.showSnackbar("Name updated") }
                                }
                            },
                            enabled = !busy && name.isNotBlank() && name.trim() != savedName,
                        ) { Text("Save") }
                    }
                }
            }
            row {
                SettingsRow(
                    title = "Matrix ID",
                    summary = userId,
                    trailing = {
                        IconButton(onClick = {
                            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Matrix ID", userId))
                            scope.launch { snackbar.showSnackbar("Matrix ID copied") }
                        }) { Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy Matrix ID") }
                    },
                )
            }
        }
        if (busy) {
            Row(Modifier.padding(horizontal = 28.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text("Saving…", style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (error.isNotBlank()) {
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 28.dp))
        }
    }
}

/** Settings → Devices: this account's sessions, with sign-out for the others (confirmed with the password). */
@Composable
internal fun DevicesScreen(
    load: suspend () -> List<SessionDevice>,
    signOut: suspend (deviceId: String, password: String?, session: String?) -> DeviceSignOut,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var reload by remember { mutableIntStateOf(0) }
    var devices by remember { mutableStateOf<List<SessionDevice>?>(null) }
    var loadError by remember { mutableStateOf("") }
    LaunchedEffect(reload) {
        try {
            devices = load()
            loadError = ""
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            loadError = failure.message ?: "Your sessions couldn't be loaded."
        }
    }
    var confirm by remember { mutableStateOf<SessionDevice?>(null) }
    var passwordFor by remember { mutableStateOf<Pair<SessionDevice, DeviceSignOut.Password>?>(null) }
    var browserFor by remember { mutableStateOf<Pair<SessionDevice, DeviceSignOut.Browser>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    fun attempt(device: SessionDevice, password: String?, session: String?) {
        launchGuarded(scope, { busy = it }, { error = it }, "This session couldn't be signed out.") {
            when (val result = signOut(device.id, password, session)) {
                DeviceSignOut.Done -> {
                    passwordFor = null
                    browserFor = null
                    reload++
                    scope.launch { snackbar.showSnackbar("Signed out of ${device.label}") }
                }
                is DeviceSignOut.Password -> passwordFor = device to result
                is DeviceSignOut.Browser -> {
                    passwordFor = null
                    browserFor = device to result
                    CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(result.url))
                }
            }
        }
    }
    SettingsPage("Devices", onBack, snackbar) {
        Text(
            "Sessions signed in to your Matrix account. Sign out of any you don't recognize; they lose access to your messages.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 4.dp),
        )
        val list = devices
        when {
            list == null && loadError.isBlank() -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            list == null -> Text(loadError, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 28.dp))
            else -> {
                SectionLabel("This device")
                SettingsGroup {
                    list.filter { it.current }.forEach { device -> row { DeviceRow(device, onSignOut = null) } }
                }
                SectionLabel("Other devices")
                val others = list.filter { !it.current }
                if (others.isEmpty()) {
                    Text(
                        "You're not signed in anywhere else.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 28.dp),
                    )
                }
                SettingsGroup {
                    others.forEach { device -> row { DeviceRow(device, onSignOut = { error = ""; confirm = device }, enabled = !busy) } }
                }
            }
        }
        if (error.isNotBlank() && passwordFor == null) {
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 28.dp))
        }
    }
    confirm?.let { device ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Sign out of ${device.label}?") },
            text = { Text("That session will be signed out and lose access to your encrypted messages.") },
            confirmButton = { TextButton(onClick = { confirm = null; attempt(device, null, null) }) { Text("Sign out") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
    passwordFor?.let { (device, request) ->
        var password by remember(request.session) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { if (!busy) passwordFor = null },
            title = { Text("Confirm it's you") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Enter your account password to sign out of ${device.label}.")
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (password.isNotEmpty()) attempt(device, password, request.session) }),
                        isError = request.wrongPassword || error.isNotBlank(),
                        supportingText = if (error.isNotBlank() || request.wrongPassword) {
                            { Text(error.ifBlank { "That password isn't right." }) }
                        } else null,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().testTag(DEVICE_PASSWORD_TAG),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { attempt(device, password, request.session) }, enabled = !busy && password.isNotEmpty()) { Text("Sign out") }
            },
            dismissButton = { TextButton(onClick = { passwordFor = null }, enabled = !busy) { Text("Cancel") } },
        )
    }
    browserFor?.let { (device, request) ->
        AlertDialog(
            onDismissRequest = { browserFor = null },
            title = { Text("Finish in your browser") },
            text = { Text("Confirm it's you on the page that opened, then come back to finish signing out of ${device.label}.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val session = request.session
                        if (session != null) attempt(device, null, session) else { browserFor = null; reload++ }
                    },
                    enabled = !busy,
                ) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { browserFor = null }) { Text("Cancel") } },
        )
    }
}

private val SessionDevice.label: String get() = name ?: id

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 28.dp, end = 28.dp, top = 4.dp),
    )
}

@Composable
private fun DeviceRow(device: SessionDevice, onSignOut: (() -> Unit)?, enabled: Boolean = true) {
    val active = when {
        device.current -> "Active now"
        device.lastSeenMillis != null -> "Last active " + DateUtils.getRelativeTimeSpanString(
            device.lastSeenMillis, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE,
        )
        else -> "Never active"
    }
    Box(Modifier.testTag("device-${device.id}")) {
        SettingsRow(
            title = device.label,
            summary = listOfNotNull(active, device.lastSeenIp?.takeIf { !device.current }, device.id.takeIf { device.name != null }).joinToString(" · "),
            icon = if (device.current) Icons.Outlined.PhoneAndroid else Icons.Outlined.Devices,
            trailing = if (onSignOut != null) {
                { TextButton(onClick = onSignOut, enabled = enabled) { Text("Sign out") } }
            } else null,
        )
    }
}
