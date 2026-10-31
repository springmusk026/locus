package dev.locus.feature.workspace.preview

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import dev.locus.core.designsystem.CodeTextStyle
import dev.locus.core.designsystem.Locus
import dev.locus.core.designsystem.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MAX_ROWS = 2_000
private const val MAX_COLS = 50
private const val MAX_CELL_COLUMNS = 40

internal class Table(val rows: List<List<String>>, val totalRows: Int, val totalCols: Int)

/**
 * Parses CSV (RFC 4180: quoted fields, doubled quotes, newlines inside quotes) or TSV. Keeps the first
 * [MAX_ROWS] rows and [MAX_COLS] columns but counts all rows, for the notice.
 */
internal fun parseTable(text: String, separator: Char): Table {
    val rows = ArrayList<List<String>>()
    var row = ArrayList<String>()
    val cell = StringBuilder()
    var quoted = false
    var total = 0
    var widest = 0
    var i = 0
    fun endRow() {
        row.add(cell.toString()); cell.setLength(0)
        if (!(row.size == 1 && row[0].isEmpty())) {
            total++
            widest = maxOf(widest, row.size)
            if (rows.size < MAX_ROWS) rows.add(if (row.size > MAX_COLS) row.subList(0, MAX_COLS).toList() else row)
        }
        row = ArrayList()
    }
    while (i < text.length) {
        val c = text[i]
        when {
            quoted && c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { cell.append('"'); i++ }
            c == '"' && (quoted || cell.isEmpty()) && separator == ',' -> quoted = !quoted
            quoted -> cell.append(c)
            c == separator -> { row.add(cell.toString()); cell.setLength(0) }
            c == '\n' -> endRow()
            c == '\r' -> Unit
            else -> cell.append(c)
        }
        i++
    }
    if (cell.isNotEmpty() || row.isNotEmpty()) endRow()
    return Table(rows, total, widest)
}

/** CSV/TSV as a table: sticky header row (the first row), columns sized to their content, sideways scrolling. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TableViewer(text: String, separator: Char, modifier: Modifier = Modifier, notices: List<String> = emptyList()) {
    val table by produceState<Table?>(null, text, separator) { value = withContext(Dispatchers.Default) { parseTable(text, separator) } }
    val t = table ?: return
    if (t.rows.isEmpty()) {
        CodeViewer(text, null, modifier, notices + "No rows found.")
        return
    }
    val style = CodeTextStyle
    val measurer = rememberTextMeasurer()
    val charWidth = remember(style) { measurer.measure(AnnotatedString("M".repeat(64)), style).size.width / 64f }
    val density = LocalDensity.current
    val cols = t.rows.maxOf { it.size }
    val pad = with(density) { Spacing.md.toPx() }
    val widthsPx = remember(t) {
        FloatArray(cols) { c ->
            val longest = t.rows.asSequence().take(500).maxOf { r -> r.getOrNull(c)?.length ?: 0 }
            longest.coerceIn(3, MAX_CELL_COLUMNS) * charWidth + pad * 2
        }
    }
    val totalWidth = widthsPx.sum()
    var viewport by remember { mutableIntStateOf(0) }
    var hOffset by remember(t) { mutableFloatStateOf(0f) }
    val hState = rememberScrollableState { delta ->
        val old = hOffset
        hOffset = (hOffset - delta).coerceIn(0f, (totalWidth - viewport).coerceAtLeast(0f))
        old - hOffset
    }
    val allNotices = buildList {
        addAll(notices)
        if (t.totalRows > MAX_ROWS) add("Showing the first ${"%,d".format(MAX_ROWS)} of ${"%,d".format(t.totalRows)} rows.")
        if (t.totalCols > MAX_COLS) add("Showing the first $MAX_COLS of ${t.totalCols} columns.")
        add("${"%,d".format(t.totalRows - 1)} rows × ${t.totalCols} columns")
    }
    val hairline = Locus.colors.hairline

    @Composable
    fun TableRow(cells: List<String>, header: Boolean) {
        Box(Modifier.fillMaxWidth().clipToBounds().background(if (header) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.background)) {
            Row(
                Modifier
                    .wrapContentWidth(Alignment.Start, unbounded = true)
                    .offset { IntOffset(-hOffset.toInt(), 0) },
            ) {
                for (c in 0 until cols) {
                    Text(
                        cells.getOrNull(c).orEmpty().replace('\n', ' '),
                        style = if (header) style.copy(fontWeight = FontWeight.SemiBold) else style,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.width(with(density) { widthsPx[c].toDp() }).padding(horizontal = Spacing.md, vertical = Spacing.xs),
                    )
                }
            }
        }
        HorizontalDivider(color = hairline)
    }

    LazyColumn(
        modifier.fillMaxSize().onSizeChanged { viewport = it.width }.scrollable(hState, Orientation.Horizontal),
        contentPadding = PaddingValues(bottom = Spacing.xxl),
    ) {
        item(key = "notice") { NoticeBanner(allNotices) }
        stickyHeader(key = "header") { TableRow(t.rows[0], header = true) }
        items(t.rows.size - 1, key = { it }) { i -> TableRow(t.rows[i + 1], header = false) }
    }
}
