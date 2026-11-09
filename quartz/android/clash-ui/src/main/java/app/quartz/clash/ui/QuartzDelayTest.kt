package app.quartz.clash.ui

import androidx.compose.runtime.Composable
import org.koin.compose.koinInject

/** The Tunnels top bar's delay test button: tests every tunnel and every Clash node. */
@Composable
fun rememberQuartzDelayTest(delays: QuartzDelays = koinInject()): () -> Unit = delays::testAll
