package app.quartz.core.ui

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.quartz.core.data.EngineProfile
import com.zaneschepke.wireguardautotunnel.R
import com.zaneschepke.wireguardautotunnel.ui.theme.AlertRed
import com.zaneschepke.wireguardautotunnel.ui.theme.Straw
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** WARP registrations last 90 days (FORK.md §9): the length of the expiry bar. */
private val WARP_LIFETIME = TimeUnit.DAYS.toMillis(90)

/** "30 Jan" (or the locale's order), without the year: expiry is always within months. */
fun quartzShortDate(millis: Long): String {
    val locale = Locale.getDefault()
    return SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "dMMM"), locale)
        .format(Date(millis))
}

/** "30 Jan 2027" (or the locale's order), with the year. */
fun quartzFullDate(millis: Long): String {
    val locale = Locale.getDefault()
    return SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "yMMMd"), locale)
        .format(Date(millis))
}

/**
 * WARP expiry as a bar of the time left (full when new, empty at expiry) with "23 days left"
 * under it (days only, no date). Yellow in the last week, red in the last day and once expired. Used on the
 * Tunnels list and in the WARP generator's list.
 */
@Composable
fun QuartzExpiryBar(
    expiresAt: Long,
    modifier: Modifier = Modifier,
    lifetime: Long = WARP_LIFETIME,
    now: Long = System.currentTimeMillis(),
) {
    val remaining = expiresAt - now
    val color =
        when {
            remaining <= TimeUnit.DAYS.toMillis(1) -> AlertRed
            remaining <= TimeUnit.DAYS.toMillis(7) -> Straw
            else -> MaterialTheme.colorScheme.primary
        }
    val left =
        when {
            remaining <= 0 -> stringResource(R.string.quartz_expired)
            remaining < TimeUnit.DAYS.toMillis(1) -> {
                val hours = TimeUnit.MILLISECONDS.toHours(remaining).coerceAtLeast(1).toInt()
                pluralStringResource(R.plurals.quartz_hours_left, hours, hours)
            }
            else -> {
                val days = TimeUnit.MILLISECONDS.toDays(remaining).toInt()
                pluralStringResource(R.plurals.quartz_days_left, days, days)
            }
        }
    QuartzBar(
        fraction = (remaining.toFloat() / lifetime).coerceIn(0f, 1f),
        color = color,
        text = left,
        textColor = if (remaining <= TimeUnit.DAYS.toMillis(7)) color else MaterialTheme.colorScheme.outline,
        modifier = modifier,
    )
}

/**
 * A Clash subscription's line(s): a bar of traffic used with "41.0 GB of 100 GB used", then
 * "expires 12 Dec 2026" (the date, no days left) and when it was last updated. Shown on the tunnel's row and in
 * its settings.
 */
@Composable
fun QuartzSubscriptionSummary(sub: EngineProfile, now: Long = System.currentTimeMillis()) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        val total = sub.totalBytes
        if (total != null && total > 0) {
            val used = sub.usedBytes ?: 0
            val fraction = (used.toFloat() / total).coerceIn(0f, 1f)
            QuartzBar(
                fraction = fraction,
                color =
                    when {
                        fraction >= 0.95f -> AlertRed
                        fraction >= 0.8f -> Straw
                        else -> MaterialTheme.colorScheme.primary
                    },
                text = stringResource(R.string.quartz_import_usage, gib(used), gib(total)),
                textColor = MaterialTheme.colorScheme.outline,
            )
        }
        val details =
            listOfNotNull(
                sub.subscriptionExpire?.let { seconds ->
                    val expires = seconds * 1000
                    if (expires <= now)
                        stringResource(R.string.quartz_expired) + " · " + quartzFullDate(expires)
                    else stringResource(R.string.quartz_expires_on, quartzFullDate(expires))
                },
                sub.lastUpdatedAt?.let {
                    stringResource(R.string.quartz_clash_last_updated, quartzShortDate(it))
                },
            )
        if (details.isNotEmpty()) {
            Text(
                details.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun QuartzBar(
    fraction: Float,
    color: Color,
    text: String,
    textColor: Color,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.widthIn(max = 220.dp).fillMaxWidth().height(4.dp),
            color = color,
            trackColor = color.copy(alpha = 0.18f),
            strokeCap = StrokeCap.Round,
        )
        Text(text, style = MaterialTheme.typography.bodySmall, color = textColor)
    }
}

private fun gib(bytes: Long) = "%.1f GB".format(bytes / 1_073_741_824.0)
