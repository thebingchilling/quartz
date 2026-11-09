package app.quartz.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.zaneschepke.wireguardautotunnel.domain.model.TunnelConfig
import com.zaneschepke.wireguardautotunnel.domain.model.TunnelGroup
import com.zaneschepke.wireguardautotunnel.ui.state.TunnelListRow
import com.zaneschepke.wireguardautotunnel.ui.state.TunnelsUiState
import com.zaneschepke.wireguardautotunnel.ui.state.buildTunnelListRows
import com.zaneschepke.wireguardautotunnel.viewmodel.SharedAppViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Hold a row and drag it to reorder, straight on the Tunnels list (no separate reorder mode),
 * as in WG Tunnel Desktop. The hold belongs to the drag handle: released without moving, it
 * selects the row as upstream's hold did; moved, it reorders. Wired into upstream's TunnelList.kt.
 */
@Stable
class QuartzTunnelDrag
internal constructor(private val order: QuartzTunnelOrder, private val scope: CoroutineScope) {
    private var draft by mutableStateOf<Pair<List<TunnelGroup>, List<TunnelConfig>>?>(null)
    private var moved = false

    /** A row was held long enough to drag. */
    fun onHold(row: TunnelListRow) {
        moved = false
    }

    /** The hold ended: without moving, it selects the row (the hold upstream used to select). */
    fun onRelease(row: TunnelListRow, viewModel: SharedAppViewModel) {
        if (moved) return
        when (row) {
            is TunnelListRow.TunnelRow -> viewModel.toggleSelectedTunnel(row.tunnel.id)
            is TunnelListRow.GroupHeader -> viewModel.toggleSelectedGroup(row.group.id)
        }
    }

    /** The rows to show: the drag's order while one is in progress or being saved. */
    fun rows(uiState: TunnelsUiState): List<TunnelListRow> =
        draft?.let { (groups, tunnels) ->
            buildTunnelListRows(groups, tunnels, uiState.backendStatus.activeTunnels.keys)
        } ?: uiState.rows

    /** Group headers move only while collapsed (their tunnels move with them), as on desktop. */
    fun canDrag(row: TunnelListRow): Boolean =
        row !is TunnelListRow.GroupHeader || !row.visibleExpanded

    /** Returns true when the rows moved. */
    fun onMove(
        fromKey: Any,
        toKey: Any,
        rows: List<TunnelListRow>,
        uiState: TunnelsUiState,
        viewModel: SharedAppViewModel,
    ): Boolean {
        val from = rows.indexOfFirst { it.key == fromKey }
        val to = rows.indexOfFirst { it.key == toKey }
        if (from < 0 || to < 0) return false
        val (groups, tunnels) = draft ?: (uiState.groups to uiState.tunnels)
        val moved =
            QuartzTunnelOrder.moveDisplayedRows(groups, tunnels, from, to, rows) ?: return false
        draft = moved
        this.moved = true
        return true
    }

    internal fun onDragStopped() {
        val (groups, tunnels) = draft ?: return
        scope.launch { order.save(groups, tunnels) }
    }

    internal fun onListChanged() {
        draft = null
    }
}

@Composable
fun rememberQuartzTunnelDrag(order: QuartzTunnelOrder = koinInject()): QuartzTunnelDrag {
    val scope = rememberCoroutineScope()
    return remember(order) { QuartzTunnelDrag(order, scope) }
}

/** Saves when a drag ends; drops the drag's order once the saved list comes back. */
@Composable
fun QuartzTunnelDragEffects(drag: QuartzTunnelDrag, dragging: Boolean, uiState: TunnelsUiState) {
    LaunchedEffect(dragging) { if (!dragging) drag.onDragStopped() }
    LaunchedEffect(uiState.tunnels, uiState.groups) { if (!dragging) drag.onListChanged() }
}
