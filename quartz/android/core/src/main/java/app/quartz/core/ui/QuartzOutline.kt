package app.quartz.core.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.quartz.bridge.EnginePlaceholder
import app.quartz.core.data.EngineProfile
import com.zaneschepke.wireguardautotunnel.ui.state.TunnelListRow

/** Which part of a group's outline one list item draws: the group spans several items. */
enum class QuartzOutlineSegment {
    SINGLE,
    TOP,
    MIDDLE,
    BOTTOM,
}

/**
 * A faint rounded outline drawn over an item, so the rows of one group read as one block. A
 * group made of several list items draws it in [segment]s: [QuartzOutlineSegment.TOP] (rounded
 * top, open bottom), MIDDLE (sides only) and BOTTOM, each reaching [gap]/2 past its edges to
 * bridge the list's spacing.
 */
fun Modifier.quartzOutline(
    segment: QuartzOutlineSegment,
    color: Color,
    start: Dp = 6.dp,
    end: Dp = 6.dp,
    gap: Dp = 5.dp,
    radius: Dp = 14.dp,
    width: Dp = 1.dp,
): Modifier = drawWithContent {
    drawContent()
    val stroke = width.toPx()
    val l = start.toPx() + stroke / 2
    val r = size.width - end.toPx() - stroke / 2
    val ext = gap.toPx() / 2
    val t =
        if (segment == QuartzOutlineSegment.SINGLE || segment == QuartzOutlineSegment.TOP) stroke / 2
        else -ext
    val b =
        if (segment == QuartzOutlineSegment.SINGLE || segment == QuartzOutlineSegment.BOTTOM)
            size.height - stroke / 2
        else size.height + ext
    if (r <= l || b <= t) return@drawWithContent
    val rad = radius.toPx().coerceAtMost((r - l) / 2).coerceAtMost((b - t) / 2)
    val path =
        Path().apply {
            when (segment) {
                QuartzOutlineSegment.SINGLE ->
                    addRoundRect(RoundRect(l, t, r, b, CornerRadius(rad, rad)))
                QuartzOutlineSegment.TOP -> {
                    moveTo(l, b)
                    lineTo(l, t + rad)
                    arcTo(Rect(l, t, l + 2 * rad, t + 2 * rad), 180f, 90f, false)
                    lineTo(r - rad, t)
                    arcTo(Rect(r - 2 * rad, t, r, t + 2 * rad), 270f, 90f, false)
                    lineTo(r, b)
                }
                QuartzOutlineSegment.MIDDLE -> {
                    moveTo(l, t)
                    lineTo(l, b)
                    moveTo(r, t)
                    lineTo(r, b)
                }
                QuartzOutlineSegment.BOTTOM -> {
                    moveTo(l, t)
                    lineTo(l, b - rad)
                    arcTo(Rect(l, b - 2 * rad, l + 2 * rad, b), 180f, -90f, false)
                    lineTo(r - rad, b)
                    arcTo(Rect(r - 2 * rad, b - 2 * rad, r, b), 90f, -90f, false)
                    lineTo(r, t)
                }
            }
        }
    drawPath(path, color, style = Stroke(stroke))
}

/** The outline colour: the theme's outline, faint. */
@Composable
fun quartzOutlineColor(): Color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)

/**
 * The outlines of one Tunnels list item (one line in upstream's TunnelList.kt): an upstream
 * group (folder) and the tunnels in it share one outline; a Clash tunnel and its proxy groups get
 * their own, inside its group's if it's in one.
 */
@Composable
fun Modifier.quartzRowOutline(rows: List<TunnelListRow>, index: Int): Modifier {
    val row = rows.getOrNull(index) ?: return this
    val color = quartzOutlineColor()
    val group: QuartzOutlineSegment? =
        when (row) {
            is TunnelListRow.GroupHeader ->
                if (row.visibleExpanded && row.childCount > 0) QuartzOutlineSegment.TOP
                else QuartzOutlineSegment.SINGLE
            is TunnelListRow.TunnelRow ->
                if (!row.grouped) null
                else {
                    val next = rows.getOrNull(index + 1)
                    if (next is TunnelListRow.TunnelRow && next.grouped && next.scopeKey == row.scopeKey)
                        QuartzOutlineSegment.MIDDLE
                    else QuartzOutlineSegment.BOTTOM
                }
        }
    val clash =
        row is TunnelListRow.TunnelRow &&
            EnginePlaceholder.engineOf(row.tunnel.quickConfig) == EngineProfile.ENGINE_MIHOMO
    var modifier = this
    if (group != null) modifier = modifier.quartzOutline(group, color)
    if (clash) {
        val grouped = (row as TunnelListRow.TunnelRow).grouped
        modifier =
            modifier.quartzOutline(
                QuartzOutlineSegment.SINGLE,
                color,
                start = if (grouped) 22.dp else 6.dp,
                end = if (grouped) 12.dp else 6.dp,
                radius = 12.dp,
            )
    }
    return modifier
}
