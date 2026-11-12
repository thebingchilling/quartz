package app.quartz.core.ui

import android.graphics.RectF
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * One card an item of the Tunnels list belongs to. A card (an upstream group, a Clash tunnel, a
 * Clash proxy group) usually spans several list items: [starts] on its first item (rounded top),
 * [ends] on its last (rounded bottom). [level] is its depth: 0 is a raised Material 3 card on the
 * list, deeper ones are tonal cards nested inside it.
 */
data class QuartzCardLayer(val level: Int, val starts: Boolean, val ends: Boolean)

/**
 * The cards item [index] belongs to, from every item's stack of card ids (outermost first): a card
 * starts where the previous item isn't in it and ends where the next one isn't.
 */
fun quartzCardLayers(stacks: List<List<String>>, index: Int): List<QuartzCardLayer> {
    val stack = stacks.getOrNull(index) ?: return emptyList()
    val prev = stacks.getOrNull(index - 1)
    val next = stacks.getOrNull(index + 1)
    return stack.mapIndexed { level, id ->
        QuartzCardLayer(
            level = level,
            starts = prev?.getOrNull(level) != id,
            ends = next?.getOrNull(level) != id,
        )
    }
}

/** Space between the list's items (TunnelList.kt's spacedBy); cards bridge it. */
val QUARTZ_LIST_GAP: Dp = 5.dp

private val OUTER_MARGIN = 12.dp
private val NEST_INSET = 8.dp
private val NEST_GAP = 6.dp
private val RADIUS = 16.dp
private val NESTED_RADIUS = 12.dp

private fun inset(level: Int): Dp = OUTER_MARGIN + NEST_INSET * level

/** Extra space above and below a raised card, so neighbouring cards don't crowd each other. */
private val OUTER_SPACE = 4.dp

/** How far below the item's top the card at [level] begins (cards starting on this item nest). */
private fun topPad(layers: List<QuartzCardLayer>, level: Int): Dp =
    (if (layers.first().starts) OUTER_SPACE else 0.dp) +
        NEST_GAP * layers.count { it.starts && it.level in 1 until level }

private fun bottomPad(layers: List<QuartzCardLayer>, level: Int): Dp =
    (if (layers.first().ends) OUTER_SPACE else 0.dp) +
        NEST_GAP * layers.count { it.ends && it.level in 1 until level }

private fun radius(level: Int): Dp = if (level == 0) RADIUS else NESTED_RADIUS

/** The card colour at [level]: raised surface, then ever higher tonal containers. */
fun ColorScheme.quartzCardColor(level: Int): Color =
    when (level) {
        0 -> surfaceContainerLow
        1 -> surfaceContainer
        2 -> surfaceContainerHigh
        else -> surfaceContainerHighest
    }

/**
 * One list item inside its [layers] of cards: draws each card's part (fill, the outer card's
 * shadow, nested cards' outline) and lays [content] out inside the innermost one, clipped to its
 * corners, with the theme's surface set to that card's colour so upstream's rows sit on the card.
 * With no layers the item is drawn as before.
 */
@Composable
fun QuartzCardItem(
    layers: List<QuartzCardLayer>,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (layers.isEmpty()) {
        Box(modifier) { content() }
        return
    }
    val scheme = MaterialTheme.colorScheme
    val inner = layers.last()
    val top = topPad(layers, inner.level)
    val bottom = bottomPad(layers, inner.level)
    val r = radius(inner.level)
    val shape =
        RoundedCornerShape(
            topStart = if (inner.starts) r else 0.dp,
            topEnd = if (inner.starts) r else 0.dp,
            bottomStart = if (inner.ends) r else 0.dp,
            bottomEnd = if (inner.ends) r else 0.dp,
        )
    val cardScheme = remember(scheme, inner.level) { scheme.copy(surface = scheme.quartzCardColor(inner.level)) }
    Box(
        modifier
            .quartzCards(layers, scheme)
            .padding(start = inset(inner.level), end = inset(inner.level), top = top, bottom = bottom)
            .clip(shape)
    ) {
        MaterialTheme(colorScheme = cardScheme, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes) {
            content()
        }
    }
}

private fun Modifier.quartzCards(layers: List<QuartzCardLayer>, scheme: ColorScheme): Modifier =
    drawWithCache {
        val gap = QUARTZ_LIST_GAP.toPx() / 2
        val far = 48.dp.toPx()
        val stroke = 1.dp.toPx()
        val shadowPaint =
            android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = scheme.quartzCardColor(0).toArgb()
                setShadowLayer(
                    3.dp.toPx(),
                    0f,
                    1.dp.toPx(),
                    Color.Black.copy(alpha = 0.22f).toArgb(),
                )
            }
        onDrawBehind {
            layers.forEach { layer ->
                val l = inset(layer.level).toPx()
                val r = size.width - l
                val topPad = topPad(layers, layer.level).toPx()
                val bottomPad = bottomPad(layers, layer.level).toPx()
                // Where this item's part of the card is visible; a card going on past the item
                // reaches into the gap, where the next item's part takes over.
                val clipTop = if (layer.starts) topPad - far else -gap
                val clipBottom = if (layer.ends) size.height - bottomPad + far else size.height + gap
                // The shape itself runs on past a continuing edge, so no corner or shadow shows there.
                val t = if (layer.starts) topPad else -gap - far
                val b = if (layer.ends) size.height - bottomPad else size.height + gap + far
                if (r <= l || b <= t) return@forEach
                val rad = radius(layer.level).toPx().coerceAtMost((r - l) / 2)
                if (layer.level == 0) {
                    clipRect(-far, clipTop, size.width + far, clipBottom) {
                        drawIntoCanvas { canvas ->
                            val path =
                                android.graphics.Path().apply {
                                    addRoundRect(
                                        RectF(l, t, r, b),
                                        floatArrayOf(rad, rad, rad, rad, rad, rad, rad, rad),
                                        android.graphics.Path.Direction.CW,
                                    )
                                }
                            canvas.nativeCanvas.drawPath(path, shadowPaint)
                        }
                    }
                } else {
                    clipRect(-far, clipTop, size.width + far, clipBottom) {
                        val path =
                            Path().apply {
                                addRoundRect(RoundRect(Rect(l, t, r, b), CornerRadius(rad, rad)))
                            }
                        drawPath(path, scheme.quartzCardColor(layer.level))
                        val edge =
                            Path().apply {
                                addRoundRect(
                                    RoundRect(
                                        Rect(l + stroke / 2, t + stroke / 2, r - stroke / 2, b - stroke / 2),
                                        CornerRadius(rad, rad),
                                    )
                                )
                            }
                        drawPath(edge, scheme.outlineVariant, style = Stroke(stroke))
                    }
                }
            }
        }
    }
