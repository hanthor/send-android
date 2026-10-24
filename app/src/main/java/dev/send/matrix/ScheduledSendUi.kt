package dev.send.matrix

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.util.Calendar

/** What the user asked for on a scheduled message's sheet. */
sealed interface ScheduledAction {
    data object SendNow : ScheduledAction
    data object Delete : ScheduledAction
    /** Replace the text and time (Messages' "Update message"). */
    data class Update(val text: String, val sendAtMillis: Long) : ScheduledAction
}

/** A quick choice on the Schedule send sheet. */
internal data class ScheduleOption(val label: String, val sendAtMillis: Long)

/**
 * Messages' quick times: later today at 6 PM and tonight at 9 PM while they are still at least a few minutes
 * away, then tomorrow morning at 8 and tomorrow afternoon at 1 (times in the device's time zone).
 */
internal fun scheduleOptions(nowMillis: Long = System.currentTimeMillis()): List<ScheduleOption> {
    fun at(dayOffset: Int, hour: Int): Long = Calendar.getInstance().apply {
        timeInMillis = nowMillis
        add(Calendar.DAY_OF_YEAR, dayOffset)
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val soonest = nowMillis + MIN_SCHEDULE_LEAD_MS
    return listOfNotNull(
        ScheduleOption("Later today, 6:00 PM", at(0, 18)).takeIf { it.sendAtMillis >= soonest },
        ScheduleOption("Later tonight, 9:00 PM", at(0, 21)).takeIf { it.sendAtMillis >= soonest },
        ScheduleOption("Tomorrow, 8:00 AM", at(1, 8)),
        ScheduleOption("Tomorrow, 1:00 PM", at(1, 13)),
    )
}

/** A schedule must be at least this far ahead. */
internal const val MIN_SCHEDULE_LEAD_MS = 60_000L

/** "Today, 6:00 PM", "Tomorrow, 8:00 AM", "Tuesday, 8:00 AM" within the week, otherwise "Oct 30, 8:00 AM". */
internal fun scheduleLabel(context: Context, sendAtMillis: Long, nowMillis: Long = System.currentTimeMillis()): String {
    val time = DateUtils.formatDateTime(context, sendAtMillis, DateUtils.FORMAT_SHOW_TIME)
    val dayDelta = dayNumber(sendAtMillis) - dayNumber(nowMillis)
    val day = when {
        dayDelta == 0L -> "Today"
        dayDelta == 1L -> "Tomorrow"
        dayDelta in 2..6 -> DateUtils.formatDateTime(context, sendAtMillis, DateUtils.FORMAT_SHOW_WEEKDAY)
        else -> DateUtils.formatDateTime(context, sendAtMillis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_NO_YEAR)
    }
    return "$day, $time"
}

private fun dayNumber(millis: Long): Long = Calendar.getInstance().run {
    timeInMillis = millis
    get(Calendar.YEAR) * 1000L + get(Calendar.DAY_OF_YEAR)
}

/**
 * Messages' "Schedule send" sheet (long-press Send): quick times, or "Pick date and time" through the Material
 * date and time pickers. [onPick] gets the chosen time; a time less than a minute away is refused in place.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScheduleSendSheet(onPick: (sendAtMillis: Long) -> Unit, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val options = remember { scheduleOptions() }
    var pickingDate by remember { mutableStateOf(false) }
    var pickedDay by remember { mutableStateOf<Long?>(null) }
    var tooSoon by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(
                "Schedule send",
                style = MaterialTheme.typography.titleMedium,
                color = colors.onSurface,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            options.forEach { option ->
                SheetRow(Icons.Outlined.Schedule, option.label) { onPick(option.sendAtMillis) }
            }
            SheetRow(Icons.Outlined.CalendarMonth, "Pick date and time") { pickingDate = true }
            if (tooSoon) {
                Text(
                    "Choose a time at least a minute from now.",
                    color = colors.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                )
            }
        }
    }
    if (pickingDate) {
        val dateState = rememberDatePickerState(initialSelectedDateMillis = utcMidnightOfToday())
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickedDay = dateState.selectedDateMillis
                        pickingDate = false
                    },
                    enabled = dateState.selectedDateMillis != null,
                ) { Text("Next") }
            },
            dismissButton = { TextButton(onClick = { pickingDate = false }) { Text("Cancel") } },
        ) { DatePicker(state = dateState) }
    }
    pickedDay?.let { day ->
        val now = Calendar.getInstance()
        val timeState = rememberTimePickerState(initialHour = (now.get(Calendar.HOUR_OF_DAY) + 1) % 24, initialMinute = 0)
        AlertDialog(
            onDismissRequest = { pickedDay = null },
            title = { Text("Pick a time") },
            text = { TimePicker(state = timeState) },
            confirmButton = {
                TextButton(onClick = {
                    val chosen = localDateTime(day, timeState.hour, timeState.minute)
                    pickedDay = null
                    if (chosen < System.currentTimeMillis() + MIN_SCHEDULE_LEAD_MS) tooSoon = true else onPick(chosen)
                }) { Text("Schedule") }
            },
            dismissButton = { TextButton(onClick = { pickedDay = null }) { Text("Cancel") } },
        )
    }
}

/** The date picker works in UTC days; today's date as that UTC midnight. */
private fun utcMidnightOfToday(): Long {
    val local = Calendar.getInstance()
    return Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH))
    }.timeInMillis
}

/** Combines a UTC-midnight day from the date picker with a local wall-clock time. */
private fun localDateTime(utcDayMillis: Long, hour: Int, minute: Int): Long {
    val utc = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcDayMillis }
    return Calendar.getInstance().apply {
        clear()
        set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH), hour, minute, 0)
    }.timeInMillis
}

/** The sheet on a scheduled message: Send now, Update message, Delete. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScheduledMessageSheet(message: ScheduledMessage, onAction: (ScheduledAction) -> Unit, onUpdate: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(
                if (message.failed) "Not sent" else "Scheduled for ${scheduleLabel(context, message.sendAtMillis)}",
                style = MaterialTheme.typography.titleMedium,
                color = if (message.failed) colors.error else colors.onSurface,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            SheetRow(Icons.AutoMirrored.Outlined.Send, "Send now") { onAction(ScheduledAction.SendNow) }
            SheetRow(Icons.Outlined.Edit, "Update message", onUpdate)
            SheetRow(Icons.Outlined.Delete, "Delete") { onAction(ScheduledAction.Delete) }
        }
    }
}

@Composable
private fun SheetRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = { Icon(icon, contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/**
 * A scheduled message in the timeline: an outlined own-side bubble (nothing has been sent) with a clock line
 * saying when it goes — "Not sent" in red once every attempt failed. Tapping opens [ScheduledMessageSheet].
 */
@Composable
internal fun ScheduledBubble(message: ScheduledMessage, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val status = if (message.failed) "Not sent" else "Scheduled for ${scheduleLabel(context, message.sendAtMillis)}"
    val described = listOfNotNull(
        message.attachments.takeIf { it.isNotEmpty() }?.let { files -> if (files.size == 1) "1 attachment" else "${files.size} attachments" },
        message.text.takeIf { it.isNotBlank() },
    ).joinToString(", ")
    Column(
        modifier.fillMaxWidth().clickable(onClickLabel = "Scheduled message options", onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .semantics(mergeDescendants = true) { contentDescription = "Scheduled message: $described. $status" },
        horizontalAlignment = Alignment.End,
    ) {
        // Messages: the photos sit above the caption; files show as tiles with their name.
        if (message.attachments.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(bottom = 4.dp)) {
                message.attachments.take(3).forEach { attachment ->
                    StagedFileTile(attachment.asShared(), Modifier.size(if (message.attachments.size == 1) 150.dp else 88.dp))
                }
                if (message.attachments.size > 3) {
                    Surface(shape = MaterialTheme.shapes.medium, color = colors.surfaceContainerHighest, modifier = Modifier.size(88.dp)) {
                        Text("+${message.attachments.size - 3}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(8.dp))
                    }
                }
            }
        }
        if (message.text.isNotBlank()) Surface(
            shape = bubbleShape(isFromMe = true, isFirstInGroup = true, isLastInGroup = true),
            color = colors.surface,
            contentColor = colors.onSurface,
            border = BorderStroke(1.dp, if (message.failed) colors.error else colors.primary),
            modifier = Modifier.widthIn(max = 280.dp),
        ) {
            Text(message.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
        }
        Spacer(Modifier.height(2.dp))
        Row(Modifier.padding(end = 14.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.Schedule,
                contentDescription = null,
                tint = if (message.failed) colors.error else colors.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(status, style = MaterialTheme.typography.labelMedium, color = if (message.failed) colors.error else colors.onSurfaceVariant)
        }
    }
}
