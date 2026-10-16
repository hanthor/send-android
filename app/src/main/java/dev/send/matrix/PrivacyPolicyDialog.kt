package dev.send.matrix

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/** The repository copy of the policy; the app bundles the same file as the `PRIVACY.md` asset. */
internal const val PRIVACY_POLICY_URL = "https://github.com/hanthor/send-android/blob/main/PRIVACY.md"

/** The bundled privacy policy (`assets/PRIVACY.md`, identical to the repository's `PRIVACY.md`). */
@Composable
fun PrivacyPolicyDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val policy = remember {
        privacyPolicyText(context.assets.open("PRIVACY.md").bufferedReader().use { it.readText() })
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Privacy policy") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(policy, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
        dismissButton = {
            TextButton(onClick = { runCatching { uriHandler.openUri(PRIVACY_POLICY_URL) } }) { Text("Open on GitHub") }
        },
    )
}

/**
 * Renders the policy's Markdown as plain text: headings bold, list items as bullets, wrapped lines joined
 * into paragraphs, and inline code/bold markers dropped.
 */
internal fun privacyPolicyText(markdown: String): AnnotatedString {
    val blocks = mutableListOf<StringBuilder>()
    var open: StringBuilder? = null
    for (raw in markdown.lines()) {
        val line = raw.trim()
        val current = open
        when {
            line.isEmpty() -> open = null
            current == null || line.startsWith("#") || line.startsWith("- ") -> {
                val block = StringBuilder(line)
                blocks += block
                open = block
            }
            else -> current.append(' ').append(line)
        }
    }
    return buildAnnotatedString {
        blocks.forEachIndexed { index, block ->
            val text = block.toString().replace("`", "").replace("**", "")
            val bullet = text.startsWith("- ")
            if (index > 0) append(if (bullet && blocks[index - 1].startsWith("- ")) "\n" else "\n\n")
            when {
                text.startsWith("#") -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text.trimStart('#').trim()) }
                bullet -> {
                    append("• ")
                    append(text.removePrefix("- "))
                }
                else -> append(text)
            }
        }
    }
}
