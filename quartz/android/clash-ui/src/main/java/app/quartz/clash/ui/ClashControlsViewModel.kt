package app.quartz.clash.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.quartz.bridge.MihomoRunner
import app.quartz.bridge.MihomoState
import app.quartz.engine.mihomo.MihomoApi
import app.quartz.engine.mihomo.MihomoMode
import app.quartz.engine.mihomo.MihomoProxies
import app.quartz.engine.mihomo.MihomoProxy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ClashControlsState(
    /** The running Clash tunnel, or null when none runs (nothing is shown then). */
    val tunnelId: Int? = null,
    val proxies: MihomoProxies? = null,
    val mode: MihomoMode = MihomoMode.RULE,
    /** Groups the user opened or closed (a lone group starts open, the rest closed). */
    val toggled: Set<String> = emptySet(),
    val sortByDelay: Boolean = false,
    val grid: Boolean = false,
    /** Delays from the last delay test (name → ms, null = failed). */
    val results: Map<String, Int?> = emptyMap(),
) {
    val groups: List<MihomoProxy>
        get() =
            proxies?.groups.orEmpty().filter {
                !it.hidden && (it.name != MihomoApi.GLOBAL || mode == MihomoMode.GLOBAL)
            }

    fun isOpen(group: MihomoProxy): Boolean = (groups.size == 1) != (group.name in toggled)

    fun delayOf(proxy: MihomoProxy): Int? =
        if (proxy.name in results) results[proxy.name] else proxy.delay

    fun members(group: MihomoProxy): List<MihomoProxy> {
        val list = group.all.mapNotNull { proxies?.byName?.get(it) }
        return if (sortByDelay) list.sortedBy { delayOf(it) ?: Int.MAX_VALUE } else list
    }
}

/**
 * The running Clash tunnel's proxy groups on the Tunnels screen (FORK.md §8): each group opens
 * to its nodes; sort by delay; list or grid.
 */
class ClashControlsViewModel(private val runner: MihomoRunner, delays: ClashDelays) :
    ViewModel() {
    private val local = MutableStateFlow(ClashControlsState())
    private var api: MihomoApi? = null

    val state: StateFlow<ClashControlsState> =
        combine(local, delays.state) { state, tests -> state.copy(results = tests.results) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ClashControlsState())

    init {
        viewModelScope.launch {
            runner.state
                .map { (it as? MihomoState.Running)?.let { r -> r.tunnelId to r.controller } }
                .distinctUntilChanged()
                .collect { running ->
                    api = running?.let { MihomoApi(it.second) }
                    local.update {
                        ClashControlsState(
                            tunnelId = running?.first,
                            sortByDelay = it.sortByDelay,
                            grid = it.grid,
                        )
                    }
                    if (running != null) refresh()
                }
        }
        // A finished delay test: reload so groups that pick their own node show the new pick.
        viewModelScope.launch {
            delays.state
                .map { it.testing }
                .distinctUntilChanged()
                .collect { testing -> if (!testing) refresh() }
        }
    }

    private fun refresh() {
        val api = api ?: return
        viewModelScope.launch {
            runCatching { api.proxies() to api.mode() }
                .onSuccess { (proxies, mode) ->
                    local.update { it.copy(proxies = proxies, mode = mode) }
                }
        }
    }

    fun toggle(group: String) = local.update {
        it.copy(toggled = if (group in it.toggled) it.toggled - group else it.toggled + group)
    }

    fun select(group: String, proxy: String) {
        val api = api ?: return
        viewModelScope.launch {
            runCatching { api.select(group, proxy) }
            refresh()
        }
    }

    fun toggleSort() = local.update { it.copy(sortByDelay = !it.sortByDelay) }

    fun toggleGrid() = local.update { it.copy(grid = !it.grid) }
}
