package com.chomugiri.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chomugiri.app.core.MathNode
import com.chomugiri.app.core.parseMath

/**
 * Lays a parsed formula out as real mathematics.
 *
 * A fraction is a genuine stack — numerator, rule, denominator — rather than "a/b", because that
 * is the whole difference between an answer you can read and one you have to decode. Everything
 * is Compose text and boxes, so it inherits the theme's colours, stays crisp at any size, and
 * costs nothing at runtime; rendering to a bitmap (what a LaTeX library would do) would go blurry
 * when scaled and would need re-rendering on every theme change.
 *
 * Serif throughout: mathematics set in the UI sans looks like a variable name, not a formula.
 */
@Composable
fun MathView(latex: String, modifier: Modifier = Modifier, display: Boolean = false) {
    val tree = remember(latex) { parseMath(latex) }
    val size = if (display) 17.sp else 15.sp
    val content: @Composable () -> Unit = {
        MathNodeView(tree, size)
    }
    if (display) {
        // A long derivation must scroll on its own rather than forcing the whole page sideways.
        Box(
            modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) { content() }
    } else {
        Box(modifier) { content() }
    }
}

@Composable
private fun MathNodeView(node: MathNode, size: TextUnit) {
    val style = TextStyle(fontFamily = FontFamily.Serif, fontSize = size)
    when (node) {
        is MathNode.Sym -> Text(node.text, style = style, color = FgPrimary)

        is MathNode.Row -> Row(verticalAlignment = Alignment.CenterVertically) {
            node.children.forEach { MathNodeView(it, size) }
        }

        is MathNode.Frac -> Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 3.dp),
        ) {
            // Slightly smaller than the surrounding line, the way real typesetting sets a
            // fraction — full-size numerals in a stack look like two separate lines of text.
            MathNodeView(node.num, size * 0.92f)
            Box(
                Modifier
                    .padding(vertical = 2.dp)
                    .height(1.dp)
                    .fillMaxWidth()
                    .background(FgPrimary),
            )
            MathNodeView(node.den, size * 0.92f)
        }

        // Raised/lowered by a fraction of the base size so the offset scales with the text
        // instead of drifting apart as the formula gets bigger.
        is MathNode.Sup -> Row(verticalAlignment = Alignment.Top) {
            MathNodeView(node.base, size)
            Box(Modifier.padding(bottom = (size.value * 0.35f).dp)) {
                MathNodeView(node.exp, size * 0.72f)
            }
        }

        is MathNode.Sub -> Row(verticalAlignment = Alignment.Bottom) {
            MathNodeView(node.base, size)
            Box(Modifier.padding(top = (size.value * 0.3f).dp)) {
                MathNodeView(node.sub, size * 0.72f)
            }
        }

        is MathNode.Sqrt -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text("√", style = style.copy(fontSize = size * 1.15f), color = FgPrimary)
            // The overbar is what makes a root read as covering its contents rather than just
            // sitting next to a tick mark.
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.height(1.dp).fillMaxWidth().background(FgPrimary))
                Box(Modifier.padding(top = 1.dp)) { MathNodeView(node.inner, size) }
            }
        }
    }
}
