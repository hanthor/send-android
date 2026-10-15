package dev.send.matrix

import android.content.Context
import android.graphics.Paint
import android.icu.lang.UCharacter
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.EmojiFlags
import androidx.compose.material.icons.outlined.EmojiFoodBeverage
import androidx.compose.material.icons.outlined.EmojiNature
import androidx.compose.material.icons.outlined.EmojiObjects
import androidx.compose.material.icons.outlined.EmojiPeople
import androidx.compose.material.icons.outlined.EmojiSymbols
import androidx.compose.material.icons.outlined.EmojiTransportation
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.emoji2.emojipicker.R as EmojiPickerR

/** Test tag of the "Search emoji" field (its merged node also carries the placeholder text). */
internal const val EmojiSearchFieldTag = "emoji_search_field"

private const val PREFS_NAME = "emoji_picker"
private const val KEY_RECENTS = "recents"
private const val KEY_STICKY_VARIANTS = "sticky_variants"
private const val MAX_RECENTS = 36

// Control characters that never occur inside an emoji sequence.
private const val ITEM_SEPARATOR = '\u001F'
private const val PAIR_SEPARATOR = '\u001E'

private val EmojiCellSize = 44.dp
private val EmojiGlyphSize = 28.dp

private class EmojiSection(val title: String, val icon: ImageVector, val rawRes: Int?)

/**
 * Tabs in Messages' order. Category contents come from the emoji2-emojipicker
 * bundled, Unicode-ordered category lists (the same data EmojiPickerView uses).
 */
private val EmojiSections = listOf(
    EmojiSection("Recently used", Icons.Outlined.Schedule, null),
    EmojiSection("Smileys & emotions", Icons.Outlined.EmojiEmotions, EmojiPickerR.raw.emoji_category_emotions),
    EmojiSection("People", Icons.Outlined.EmojiPeople, EmojiPickerR.raw.emoji_category_people_gender_inclusive),
    EmojiSection("Animals & nature", Icons.Outlined.EmojiNature, EmojiPickerR.raw.emoji_category_animals_nature),
    EmojiSection("Food & drink", Icons.Outlined.EmojiFoodBeverage, EmojiPickerR.raw.emoji_category_food_drink),
    EmojiSection("Travel & places", Icons.Outlined.EmojiTransportation, EmojiPickerR.raw.emoji_category_travel_places),
    EmojiSection("Activities", Icons.Outlined.EmojiEvents, EmojiPickerR.raw.emoji_category_activity),
    EmojiSection("Objects", Icons.Outlined.EmojiObjects, EmojiPickerR.raw.emoji_category_objects),
    EmojiSection("Symbols", Icons.Outlined.EmojiSymbols, EmojiPickerR.raw.emoji_category_symbols),
    EmojiSection("Flags", Icons.Outlined.EmojiFlags, EmojiPickerR.raw.emoji_category_flags),
)

/**
 * Google Messages-style emoji panel: "Search emoji" field, category tabs and a
 * sectioned grid (recents first). Long-pressing an emoji marked with a corner
 * triangle opens its skin-tone / gender variants; the chosen variant sticks.
 * Fills the size it is given.
 */
@Composable
internal fun EmojiPickerPanel(onEmoji: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val session by produceState<EmojiPickerSession?>(initialValue = null, context) {
        val appContext = context.applicationContext
        value = withContext(Dispatchers.IO) {
            EmojiPickerSession(EmojiDataStore.load(appContext), EmojiPrefs(appContext))
        }
    }
    val currentOnEmoji by rememberUpdatedState(onEmoji)
    var query by rememberSaveable { mutableStateOf("") }

    Column(modifier.background(MaterialTheme.colorScheme.surfaceContainer)) {
        EmojiSearchField(
            query = query,
            onQueryChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
        )
        val loaded = session
        if (loaded != null) {
            EmojiPickerContent(
                session = loaded,
                query = query,
                onEmoji = { currentOnEmoji(it) },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun EmojiPickerContent(
    session: EmojiPickerSession,
    query: String,
    onEmoji: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sticky = remember(session) {
        mutableStateMapOf<String, String>().apply { putAll(session.initialStickyVariants) }
    }
    val pick: (String) -> Unit = { emoji ->
        session.prefs.addRecent(emoji)
        onEmoji(emoji)
    }
    val pickVariant: (String, String) -> Unit = { base, variant ->
        sticky[base] = variant
        session.prefs.saveStickyVariants(sticky.toMap())
        pick(variant)
    }
    // Recents are a snapshot taken when the panel opens so cells never move under the finger.
    val sections = remember(session) {
        listOf(session.recents.map { EmojiEntry(it, listOf(it)) }) + session.data.categories
    }
    val sectionStarts = remember(sections) {
        var index = 0
        sections.map { entries ->
            val start = index
            index += 1 + maxOf(entries.size, 1)
            start
        }
    }
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()

    Column(modifier) {
        if (query.isBlank()) {
            val selected by remember(sectionStarts, gridState) {
                derivedStateOf {
                    val first = gridState.firstVisibleItemIndex
                    sectionStarts.indexOfLast { it <= first }.coerceAtLeast(0)
                }
            }
            EmojiCategoryTabs(
                selected = selected,
                onSelect = { section -> scope.launch { gridState.animateScrollToItem(sectionStarts[section]) } },
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            EmojiCategoryGrid(
                sections = sections,
                state = gridState,
                sticky = sticky,
                onPick = pick,
                onPickVariant = pickVariant,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )
        } else {
            val results = remember(session, query) { session.data.search(query) }
            EmojiSearchResults(
                query = query,
                results = results,
                sticky = sticky,
                onPick = pick,
                onPickVariant = pickVariant,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun EmojiSearchField(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val textStyle = MaterialTheme.typography.bodyLarge
    val focusManager = LocalFocusManager.current
    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.testTag(EmojiSearchFieldTag),
        singleLine = true,
        textStyle = textStyle.copy(color = colors.onSurface),
        cursorBrush = SolidColor(colors.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier
                    .height(48.dp)
                    .clip(CircleShape)
                    .background(colors.surfaceContainerHighest)
                    .padding(start = 16.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Search,
                    contentDescription = null,
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(12.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text(
                            text = "Search emoji",
                            style = textStyle,
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    innerTextField()
                }
                if (query.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .clickable(role = Role.Button) { onQueryChange("") },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = "Clear search",
                            tint = colors.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun EmojiCategoryTabs(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .selectableGroup(),
    ) {
        EmojiSections.forEachIndexed { index, section ->
            val isSelected = index == selected
            val background by animateColorAsState(
                targetValue = if (isSelected) colors.secondaryContainer else Color.Transparent,
                animationSpec = SendMotion.effectsDefault(),
                label = "emojiTabBackground",
            )
            val tint by animateColorAsState(
                targetValue = if (isSelected) colors.onSecondaryContainer else colors.onSurfaceVariant,
                animationSpec = SendMotion.effectsDefault(),
                label = "emojiTabTint",
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .padding(horizontal = 2.dp, vertical = 4.dp)
                    .clip(CircleShape)
                    .background(background)
                    .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(index) }),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = section.icon,
                    contentDescription = section.title,
                    tint = tint,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun EmojiCategoryGrid(
    sections: List<List<EmojiEntry>>,
    state: LazyGridState,
    sticky: Map<String, String>,
    onPick: (String) -> Unit,
    onPickVariant: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(EmojiCellSize),
        state = state,
        modifier = modifier,
        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 8.dp),
    ) {
        sections.forEachIndexed { sectionIndex, entries ->
            item(
                key = "header:$sectionIndex",
                span = { GridItemSpan(maxLineSpan) },
                contentType = "header",
            ) {
                Text(
                    text = EmojiSections[sectionIndex].title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 4.dp),
                )
            }
            if (entries.isEmpty()) {
                item(
                    key = "empty:$sectionIndex",
                    span = { GridItemSpan(maxLineSpan) },
                    contentType = "empty",
                ) {
                    Text(
                        text = if (sectionIndex == 0) "No recently used emoji" else "No emoji available",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                    )
                }
            } else {
                items(
                    count = entries.size,
                    key = { index -> "emoji:$sectionIndex:$index" },
                    contentType = { "emoji" },
                ) { index ->
                    val entry = entries[index]
                    EmojiCell(
                        emoji = sticky[entry.base] ?: entry.base,
                        variants = entry.variants,
                        onPick = onPick,
                        onPickVariant = { variant -> onPickVariant(entry.base, variant) },
                    )
                }
            }
        }
    }
}

@Composable
private fun EmojiSearchResults(
    query: String,
    results: List<EmojiSearchHit>,
    sticky: Map<String, String>,
    onPick: (String) -> Unit,
    onPickVariant: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (results.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(
                text = "No emoji found",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    // Each new query starts at the best matches.
    val state = rememberLazyGridState()
    LaunchedEffect(query) { state.scrollToItem(0) }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(EmojiCellSize),
        state = state,
        modifier = modifier,
        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 8.dp),
    ) {
        items(count = results.size, contentType = { "emoji" }) { index ->
            val hit = results[index]
            EmojiCell(
                emoji = sticky[hit.emoji] ?: hit.emoji,
                variants = hit.variants,
                onPick = onPick,
                onPickVariant = { variant -> onPickVariant(hit.emoji, variant) },
            )
        }
    }
}

/** One clickable emoji; its merged semantics text is the emoji itself. */
@Composable
private fun EmojiCell(
    emoji: String,
    variants: List<String>,
    onPick: (String) -> Unit,
    onPickVariant: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showVariants by remember { mutableStateOf(false) }
    val hasVariants = variants.size > 1
    val openVariants: (() -> Unit)? = if (hasVariants) ({ showVariants = true }) else null
    val indicatorColor = MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(
                onLongClickLabel = if (hasVariants) "Show variants" else null,
                onLongClick = openVariants,
                onClick = { onPick(emoji) },
            ),
        contentAlignment = Alignment.Center,
    ) {
        EmojiGlyph(emoji)
        if (hasVariants) {
            Canvas(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(5.dp)
                    .size(5.dp),
            ) {
                val triangle = Path().apply {
                    moveTo(size.width, 0f)
                    lineTo(size.width, size.height)
                    lineTo(0f, size.height)
                    close()
                }
                drawPath(triangle, indicatorColor)
            }
        }
        if (showVariants) {
            EmojiVariantPopup(
                variants = variants,
                onDismiss = { showVariants = false },
                onPick = { variant ->
                    showVariants = false
                    onPickVariant(variant)
                },
            )
        }
    }
}

@Composable
private fun EmojiGlyph(emoji: String) {
    // Sized in dp so emoji always fit their cells regardless of the font scale.
    val glyphSize = with(LocalDensity.current) { EmojiGlyphSize.toSp() }
    Text(
        text = emoji,
        fontSize = glyphSize,
        lineHeight = glyphSize,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        softWrap = false,
    )
}

@Composable
private fun EmojiVariantPopup(variants: List<String>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val gap = with(LocalDensity.current) { 4.dp.roundToPx() }
    val positionProvider = remember(gap) { AboveAnchorPositionProvider(gap) }
    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier.padding(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                variantRows(variants).forEach { row ->
                    Row(horizontalArrangement = Arrangement.Center) {
                        row.forEach { variant ->
                            Box(
                                modifier = Modifier
                                    .size(EmojiCellSize)
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { onPick(variant) },
                                contentAlignment = Alignment.Center,
                            ) {
                                EmojiGlyph(variant)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Skin tones in columns: base + 5 tones per row, or a 5x5 grid of two-person tone pairs under the base. */
private fun variantRows(variants: List<String>): List<List<String>> = when {
    variants.size % 6 == 0 -> variants.chunked(6)
    variants.size > 6 && (variants.size - 1) % 5 == 0 -> listOf(listOf(variants.first())) + variants.drop(1).chunked(5)
    else -> variants.chunked(6)
}

/** Places the popup centred above its anchor, or below it when there is no room above. */
private class AboveAnchorPositionProvider(private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        val x = (anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2).coerceIn(0, maxX)
        val above = anchorBounds.top - popupContentSize.height - gap
        val y = if (above >= 0) above else anchorBounds.bottom + gap
        return IntOffset(x, y)
    }
}

/** A grid emoji: [variants] starts with [base] and lists every renderable skin-tone/gender variant. */
private class EmojiEntry(val base: String, val variants: List<String>)

/** A searchable emoji with its skin-tone variants and lowercase name words. */
private class EmojiSearchHit(val emoji: String, val variants: List<String>, val words: List<String>)

private class EmojiData(val categories: List<List<EmojiEntry>>, private val index: List<EmojiSearchHit>) {
    /** Every query word must prefix a word of the emoji's Unicode name (or flag country name). */
    fun search(query: String): List<EmojiSearchHit> {
        val tokens = query.lowercase(Locale.ROOT).split(' ', '-', ',').filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return emptyList()
        val matches = ArrayList<Pair<Int, EmojiSearchHit>>()
        for (hit in index) {
            var score = 0
            var matched = true
            for (token in tokens) {
                val word = hit.words.indexOfFirst { it.startsWith(token) }
                if (word < 0) {
                    matched = false
                    break
                }
                score += if (hit.words[word] == token) word else word + 4
            }
            if (matched) matches += score to hit
        }
        matches.sortBy { it.first }
        return matches.map { it.second }
    }
}

private class EmojiPickerSession(val data: EmojiData, val prefs: EmojiPrefs) {
    val recents: List<String> = prefs.recents()
    val initialStickyVariants: Map<String, String> = prefs.stickyVariants()
}

private class EmojiPrefs(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun recents(): List<String> =
        prefs.getString(KEY_RECENTS, null)?.split(ITEM_SEPARATOR)?.filter { it.isNotEmpty() }.orEmpty()

    fun addRecent(emoji: String) {
        val updated = (listOf(emoji) + recents().filter { it != emoji }).take(MAX_RECENTS)
        prefs.edit().putString(KEY_RECENTS, updated.joinToString(ITEM_SEPARATOR.toString())).apply()
    }

    fun stickyVariants(): Map<String, String> =
        prefs.getString(KEY_STICKY_VARIANTS, null)
            ?.split(ITEM_SEPARATOR)
            ?.mapNotNull { pair ->
                val split = pair.indexOf(PAIR_SEPARATOR)
                if (split <= 0) null else pair.substring(0, split) to pair.substring(split + 1)
            }
            ?.toMap()
            .orEmpty()

    fun saveStickyVariants(variants: Map<String, String>) {
        val encoded = variants.entries.joinToString(ITEM_SEPARATOR.toString()) { (base, variant) ->
            "$base$PAIR_SEPARATOR$variant"
        }
        prefs.edit().putString(KEY_STICKY_VARIANTS, encoded).apply()
    }
}

/** Parses the bundled category lists once per process, keeping only emoji the platform font renders. */
private object EmojiDataStore {
    @Volatile
    private var cached: EmojiData? = null

    fun load(context: Context): EmojiData =
        cached ?: synchronized(this) { cached ?: parse(context).also { cached = it } }

    private fun parse(context: Context): EmojiData {
        val paint = Paint()
        val categories = EmojiSections.mapNotNull { it.rawRes }.map { rawRes ->
            context.resources.openRawResource(rawRes).bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.mapNotNull { line -> parseLine(line, paint) }.toList()
            }
        }
        val index = ArrayList<EmojiSearchHit>()
        val seen = HashSet<String>()
        for (entries in categories) {
            for (entry in entries) {
                for (emoji in entry.variants) {
                    if (hasSkinTone(emoji) || !seen.add(emoji)) continue
                    val skinTones = entry.variants.filter { it == emoji || stripSkinTones(it) == emoji }
                    index += EmojiSearchHit(emoji, skinTones, nameWords(emoji))
                }
            }
        }
        return EmojiData(categories, index)
    }

    /** CSV line: base emoji followed by its variants (the base may repeat). */
    private fun parseLine(line: String, paint: Paint): EmojiEntry? {
        val parts = line.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val base = parts.firstOrNull() ?: return null
        if (!paint.hasGlyph(base)) return null
        val variants = (listOf(base) + parts.drop(1)).distinct().filter { it == base || paint.hasGlyph(it) }
        return EmojiEntry(base, variants)
    }

    private fun isSkinTone(codePoint: Int) = codePoint in 0x1F3FB..0x1F3FF

    private fun hasSkinTone(emoji: String): Boolean {
        var i = 0
        while (i < emoji.length) {
            val codePoint = emoji.codePointAt(i)
            if (isSkinTone(codePoint)) return true
            i += Character.charCount(codePoint)
        }
        return false
    }

    private fun stripSkinTones(emoji: String): String {
        val out = StringBuilder(emoji.length)
        var i = 0
        while (i < emoji.length) {
            val codePoint = emoji.codePointAt(i)
            if (!isSkinTone(codePoint)) out.appendCodePoint(codePoint)
            i += Character.charCount(codePoint)
        }
        return out.toString()
    }

    /** Lowercase search words from ICU character names, plus country names for flags. */
    private fun nameWords(emoji: String): List<String> {
        val name = StringBuilder()
        val regional = StringBuilder()
        val tag = StringBuilder()
        var i = 0
        while (i < emoji.length) {
            val codePoint = emoji.codePointAt(i)
            i += Character.charCount(codePoint)
            when (codePoint) {
                in 0x1F1E6..0x1F1FF -> regional.append('a' + (codePoint - 0x1F1E6))
                in 0xE0061..0xE007A -> tag.append('a' + (codePoint - 0xE0061))
                in 0xE0020..0xE007F, in 0x1F3FB..0x1F3FF, 0x200D, 0xFE0E, 0xFE0F -> Unit
                0x20E3 -> name.append(" keycap")
                else -> UCharacter.getName(codePoint)?.let { name.append(' ').append(it) }
            }
        }
        val region = when {
            regional.length == 2 -> regional.toString()
            tag.length >= 2 -> tag.substring(0, 2)
            else -> null
        }
        if (region != null) {
            name.append(" flag ").append(region)
            val locale = runCatching { Locale.Builder().setRegion(region.uppercase(Locale.ROOT)).build() }.getOrNull()
            if (locale != null) {
                name.append(' ').append(locale.getDisplayCountry(Locale.getDefault()))
                name.append(' ').append(locale.getDisplayCountry(Locale.ENGLISH))
            }
        }
        return name.toString()
            .lowercase(Locale.ROOT)
            .split(' ', '-', ',', '(', ')', '.')
            .filter { it.isNotEmpty() }
            .distinct()
    }
}
