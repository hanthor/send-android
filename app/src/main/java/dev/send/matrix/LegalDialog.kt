package dev.send.matrix

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext

/** AGPL Appropriate Legal Notices plus the bundled third-party attributions. */
@Composable
fun LegalNoticesDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val notices = remember {
        context.assets.open("THIRD_PARTY_NOTICES.txt").bufferedReader().readText()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("About & licenses") },
        text = {
            androidx.compose.foundation.layout.Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Copyright (C) 2026 Send contributors.\n\n" +
                        "Send is free software: you can redistribute it and/or modify it under " +
                        "the terms of the GNU Affero General Public License as published by " +
                        "the Free Software Foundation, version 3. This program is distributed " +
                        "in the hope that it will be useful, but WITHOUT ANY WARRANTY. " +
                        "The full license ships with the app (LICENSE.txt) and at " +
                        "https://www.gnu.org/licenses/. " +
                        "Corresponding source: https://github.com/hanthor/send-android",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    notices,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}
