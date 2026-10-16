package dev.send.matrix

import android.graphics.BitmapFactory
import android.text.SpannableString
import android.text.style.URLSpan
import android.text.util.Linkify
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupPositionProvider
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A web link found in message text: [start]/[end] index the text, [url] is normalised (scheme added). */
internal data class TextLink(val start: Int, val end: Int, val url: String)

/** Web links in [text], found the way Android's own text views do (`Linkify.WEB_URLS`). */
internal fun findLinks(text: String): List<TextLink> {
    if (text.isBlank()) return emptyList()
    val spannable = SpannableString(text)
    if (!Linkify.addLinks(spannable, Linkify.WEB_URLS)) return emptyList()
    return spannable.getSpans(0, spannable.length, URLSpan::class.java)
        .map { TextLink(spannable.getSpanStart(it), spannable.getSpanEnd(it), it.url) }
        .sortedBy { it.start }
}

/** Ranges of [query] in [text], case-insensitive (in-conversation search). */
internal fun matchRanges(text: String, query: String): List<IntRange> {
    if (query.isBlank()) return emptyList()
    val ranges = ArrayList<IntRange>()
    var from = 0
    while (true) {
        val index = text.indexOf(query, from, ignoreCase = true)
        if (index < 0) break
        ranges += index until index + query.length
        from = index + query.length
    }
    return ranges
}

/** Message text with tappable links and highlighted search matches. */
internal fun messageText(text: String, links: List<TextLink>, query: String, linkColor: Color, highlight: Color): AnnotatedString =
    buildAnnotatedString {
        append(text)
        val linkStyle = TextLinkStyles(style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
        for (link in links) addLink(LinkAnnotation.Url(link.url, linkStyle), link.start, link.end)
        for (range in matchRanges(text, query)) {
            addStyle(SpanStyle(background = highlight, fontWeight = FontWeight.SemiBold), range.first, range.last + 1)
        }
    }

/**
 * Messages' quoted message inside a reply bubble: sender and a two-line snippet on a tinted block with an
 * accent bar. Tapping jumps to the original; a long press selects the reply like the rest of the bubble.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun ReplyQuoteBlock(quote: ReplyQuote, isFromMe: Boolean, onClick: () -> Unit, onLongClick: () -> Unit = {}, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val content = if (isFromMe) colors.onPrimaryContainer else colors.onSurface
    val sender = when {
        quote.own -> "You"
        quote.sender != null -> quote.sender
        else -> "Original message"
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(content.copy(alpha = 0.08f))
            .combinedClickable(onClickLabel = "Go to original message", onClick = onClick, onLongClick = onLongClick)
            .semantics { contentDescription = "Reply to $sender" }
            .heightIn(min = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(4.dp).height(40.dp).background(if (isFromMe) colors.primary else colors.tertiary))
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text(sender, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = content, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                quote.text ?: if (quote.sender == null) "Loading…" else "Message unavailable",
                style = MaterialTheme.typography.bodyMedium,
                color = content.copy(alpha = 0.8f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Muted placeholder for a redacted message, as Messages shows deleted RCS messages. */
@Composable
internal fun DeletedBubble(isFromMe: Boolean, shape: Shape, isLastInGroup: Boolean) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = if (isLastInGroup) 4.dp else 1.dp),
        contentAlignment = if (isFromMe) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Surface(
            shape = shape,
            color = Color.Transparent,
            contentColor = colors.onSurfaceVariant,
            border = BorderStroke(1.dp, colors.outlineVariant),
        ) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Block, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Message deleted", style = MaterialTheme.typography.bodyLarge, fontStyle = FontStyle.Italic)
            }
        }
    }
}

/** Messages' incoming "typing" bubble: three dots pulsing in sequence. */
@Composable
internal fun TypingBubble(names: List<String>, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val transition = rememberInfiniteTransition(label = "typing")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(tween(1_200, easing = LinearEasing), RepeatMode.Restart),
        label = "typingPhase",
    )
    val description = when (names.size) {
        0 -> "Typing"
        1 -> "${names[0]} is typing"
        2 -> "${names[0]} and ${names[1]} are typing"
        else -> "${names.size} people are typing"
    }
    Box(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), contentAlignment = Alignment.CenterStart) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = colors.surfaceContainerHigh,
            modifier = Modifier.semantics { contentDescription = description },
        ) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                repeat(3) { dot ->
                    // Each dot rises and brightens in turn.
                    val distance = ((phase - dot + 3f) % 3f)
                    val lift = if (distance < 1f) kotlin.math.sin(distance * Math.PI).toFloat() else 0f
                    Box(
                        Modifier
                            .size(8.dp)
                            .graphicsLayer { translationY = -lift * 4.dp.toPx() }
                            .background(colors.onSurfaceVariant.copy(alpha = 0.4f + 0.5f * lift), CircleShape),
                    )
                }
            }
        }
    }
}

/** Decodes a link preview image at a small size off the main thread. */
private suspend fun decodePreviewImage(file: File): ImageBitmap? = withContext(Dispatchers.IO) {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, 560) }
    BitmapFactory.decodeFile(file.absolutePath, options)?.asImageBitmap()
}

/** Messages' rich link card under the text: image, title, description and site; tapping opens the link. */
@Composable
internal fun LinkPreviewCard(preview: LinkPreview, isFromMe: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val uriHandler = LocalUriHandler.current
    val content = if (isFromMe) colors.onPrimaryContainer else colors.onSurface
    val image by produceState<ImageBitmap?>(null, preview.image) {
        value = preview.image?.let { runCatching { decodePreviewImage(it) }.getOrNull() }
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surfaceContainerLowest.copy(alpha = if (isFromMe) 0.55f else 0.8f))
            .clickable(onClickLabel = "Open link") { runCatching { uriHandler.openUri(preview.url) } }
            .semantics { contentDescription = "Link preview: ${preview.title ?: preview.url}" },
    ) {
        image?.let { bitmap ->
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(140.dp),
            )
        }
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            preview.title?.let {
                Text(it, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = content, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            preview.description?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = content.copy(alpha = 0.8f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            val site = preview.siteName ?: runCatching { java.net.URI(preview.url).host?.removePrefix("www.") }.getOrNull()
            site?.let {
                Spacer(Modifier.height(2.dp))
                Text(it, style = MaterialTheme.typography.labelSmall, color = content.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * Messages' swipe-to-reply: the bubble follows the finger to the right with resistance, a reply arrow fades in
 * behind it, a haptic tick marks the threshold, and the bubble springs back on release.
 */
@Composable
internal fun SwipeToReply(enabled: Boolean, onReply: () -> Unit, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val threshold = rememberSwipeThreshold()
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    val thresholdPx = with(density) { REPLY_SWIPE_THRESHOLD.toPx() }
    val maxPx = with(density) { 104.dp.toPx() }
    val reply by androidx.compose.runtime.rememberUpdatedState(onReply)
    Box(
        modifier = Modifier.fillMaxWidth().pointerInput(enabled) {
            if (!enabled) return@pointerInput
            var dragged = 0f
            detectHorizontalDragGestures(
                onDragStart = { dragged = 0f },
                onDragEnd = {
                    if (threshold.release()) reply()
                    scope.launch { offset.animateTo(0f, SendMotion.swipeSettle()) }
                },
                onDragCancel = {
                    threshold.release()
                    scope.launch { offset.animateTo(0f, SendMotion.swipeSettle()) }
                },
            ) { change, dragAmount ->
                dragged = (dragged + dragAmount).coerceAtLeast(0f)
                // Past the threshold the bubble moves at a third of the finger's speed.
                val shown = if (dragged <= thresholdPx) dragged else thresholdPx + (dragged - thresholdPx) / 3f
                change.consume()
                scope.launch { offset.snapTo(shown.coerceAtMost(maxPx)) }
                threshold.update(dragged >= thresholdPx)
            }
        },
    ) {
        val progress = (offset.value / thresholdPx).coerceIn(0f, 1f)
        if (offset.value > 0f) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 20.dp)
                    .size(32.dp)
                    .graphicsLayer {
                        alpha = progress
                        scaleX = 0.6f + 0.4f * progress
                        scaleY = 0.6f + 0.4f * progress
                    }
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp))
            }
        }
        Box(Modifier.fillMaxWidth().offset { IntOffset(offset.value.roundToInt(), 0) }) { content() }
    }
}

internal val REPLY_SWIPE_THRESHOLD = 64.dp

/**
 * Who has read up to [messages][index]: receipts on that event or any later one (the SDK keeps each person's
 * receipt on the newest event they read), plus anyone who sent a later message.
 */
internal fun readersFrom(messages: List<MatrixMessage>, index: Int): Map<String, Long> {
    if (index !in messages.indices) return emptyMap()
    val readers = LinkedHashMap<String, Long>()
    for (message in messages.subList(index, messages.size)) {
        for ((user, time) in message.readBy) readers.putIfAbsent(user, time)
        if (!message.own && message.call == null) readers.putIfAbsent(message.sender, message.timestampMillis)
    }
    return readers
}

/** Delivery label for an own message: Sending…/Not sent/Read/Seen by N/Sent, Messages' wording. */
internal fun deliveryLabel(message: MatrixMessage, readers: Int, isDirect: Boolean): String = when {
    message.status == DeliveryStatus.Sending -> "Sending…"
    message.status == DeliveryStatus.Failed -> "Not sent"
    readers > 0 && isDirect -> "Read"
    readers > 0 -> "Seen by $readers"
    else -> "Sent"
}
