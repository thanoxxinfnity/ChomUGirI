package com.chomugiri.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chomugiri.app.core.InlineSpan
import com.chomugiri.app.core.MdBlock
import com.chomugiri.app.core.parseInline
import com.chomugiri.app.core.parseMarkdown

/**
 * Renders an assistant reply the way it was written: headings as headings, lists as lists, tables
 * as tables, and maths as maths. Every one of those used to print its own markdown source.
 *
 * Built out of ordinary Compose rather than a WebView or a markdown library: it inherits the
 * theme, needs no network for a CDN (the app's pages are offline-capable and a blocked stylesheet
 * would leave a wall of unstyled text), and adds no dependency to the build.
 */
@Composable
fun MarkdownView(text: String, modifier: Modifier = Modifier) {
    val blocks = remember(text) { parseMarkdown(text) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { BlockView(it) }
    }
}

@Composable
private fun BlockView(block: MdBlock) {
    when (block) {
        is MdBlock.Heading -> Text(
            block.text,
            style = when (block.level) {
                1 -> MaterialTheme.typography.titleLarge
                2 -> MaterialTheme.typography.titleMedium
                else -> MaterialTheme.typography.titleSmall
            },
            color = FgPrimary,
            modifier = Modifier.padding(top = 4.dp),
        )

        is MdBlock.Paragraph -> InlineText(block.text)

        is MdBlock.ListItem -> Row(
            Modifier.padding(start = (block.depth * 14).dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                block.marker,
                style = MaterialTheme.typography.bodyLarge,
                // The marker is the one part that should recede — it is structure, not content.
                color = if (block.ordered) Accent else FgMuted,
                modifier = Modifier.widthIn(min = 20.dp),
            )
            Spacer(Modifier.width(2.dp))
            InlineText(block.text, Modifier.weight(1f))
        }

        is MdBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(Accent.copy(alpha = 0.55f)))
            Spacer(Modifier.width(10.dp))
            InlineText(block.text, color = FgMuted)
        }

        is MdBlock.Rule -> HorizontalDivider(color = BorderCol, modifier = Modifier.padding(vertical = 4.dp))

        is MdBlock.MathDisplay -> MathView(block.latex, display = true)

        // Scrolls inside itself: a wide table must never push the whole message sideways.
        is MdBlock.Table -> Box(Modifier.horizontalScroll(rememberScrollState())) {
            Column(
                Modifier
                    .background(BgElevated2, RoundedCornerShape(10.dp))
                    .padding(2.dp),
            ) {
                TableRow(block.header, header = true)
                block.rows.forEach { r ->
                    HorizontalDivider(color = BorderCol)
                    TableRow(r, header = false)
                }
            }
        }
    }
}

@Composable
private fun TableRow(cells: List<String>, header: Boolean) {
    Row {
        cells.forEach { c ->
            Box(Modifier.width(132.dp).padding(horizontal = 10.dp, vertical = 7.dp)) {
                Text(
                    c,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
                    ),
                    color = if (header) FgPrimary else FgMuted,
                )
            }
        }
    }
}

/**
 * One paragraph's worth of inline runs.
 *
 * Maths cannot live inside an AnnotatedString — a stacked fraction is a layout, not a styled
 * character run — so a line is laid out as a FlowRow of pieces, letting a formula sit inline with
 * the words around it and wrap with them.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InlineText(text: String, modifier: Modifier = Modifier, color: androidx.compose.ui.graphics.Color? = null) {
    val spans = remember(text) { parseInline(text) }
    val fg = color ?: FgPrimary
    val codeFg = Accent
    val codeBg = BgElevated2

    // The overwhelmingly common case is a line with no maths at all; keeping that on a single
    // Text preserves normal justification and selection rather than breaking it into fragments.
    if (spans.none { it.math != null }) {
        Text(
            buildAnnotated(spans, fg, codeFg, codeBg),
            style = MaterialTheme.typography.bodyLarge,
            modifier = modifier,
        )
        return
    }

    androidx.compose.foundation.layout.FlowRow(
        modifier,
        verticalArrangement = Arrangement.Center,
    ) {
        spans.forEach { s ->
            if (s.math != null) {
                MathView(s.math, Modifier.padding(horizontal = 2.dp))
            } else {
                Text(
                    buildAnnotated(listOf(s), fg, codeFg, codeBg),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

/**
 * Theme colours arrive as parameters, not by reading the palette here: they are @Composable
 * getters, and this builder deliberately is not composable so it can be called from inside a
 * remember-free text build.
 */
private fun buildAnnotated(
    spans: List<InlineSpan>,
    fg: androidx.compose.ui.graphics.Color,
    codeFg: androidx.compose.ui.graphics.Color,
    codeBg: androidx.compose.ui.graphics.Color,
) =
    androidx.compose.ui.text.buildAnnotatedString {
        spans.forEach { s ->
            if (s.math != null) return@forEach
            val style = androidx.compose.ui.text.SpanStyle(
                fontWeight = if (s.bold) FontWeight.Bold else null,
                fontStyle = if (s.italic) FontStyle.Italic else null,
                fontFamily = if (s.code) FontFamily.Monospace else null,
                fontSize = if (s.code) 13.5.sp else androidx.compose.ui.unit.TextUnit.Unspecified,
                color = if (s.code) codeFg else fg,
                background = if (s.code) codeBg else androidx.compose.ui.graphics.Color.Transparent,
            )
            withStyle(style) { append(s.text) }
        }
    }
