package app.quartz.clash.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.quartz.bridge.MihomoRunner
import app.quartz.bridge.MihomoState
import app.quartz.engine.mihomo.MihomoApi
import app.quartz.engine.mihomo.MihomoMode
import app.quartz.engine.mihomo.MihomoProxies
import app.quartz.engine.mihomo.MihomoProxy
import com.zaneschepke.wireguardautotunnel.domain.repository.TunnelRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class ClashControlsState(
    /** The running Clash tunnel, or null when none runs (the panel is hidden then). */
    val tunnelId: Int? = null,
    val proxies: MihomoProxies? = null,
    val mode: MihomoMode = MihomoMode.RULE,
    val expanded: Set<String> = emptySet(),
    /** Delays from the current "test all" run, shown as they come in (name → ms, null = failed). */
    val results: Map<String, Int?> = emptyMap(),
    val testing: Boolean = false,
    val tested: Int = 0,
    val toTest: Int = 0,
    val query: String = "",
    val sortByDelay: Boolean = false,
    val error: String? = null,
) {
    fun delayOf(proxy: MihomoProxy): Int? =
        if (proxy.name in results) results[proxy.name] else proxy.delay
}

/**
 * The Clash controls on the Tunnels screen (FORK.md §8): mode, every group with its nodes, and one
 * delay test for all nodes, for whichever Clash tunnel is running.
 */
class ClashControlsViewModel(
    private val runner: MihomoRunner,
    tunnelRepository: TunnelRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(ClashControlsState())
    val state: StateFlow<ClashControlsState> = _state.asStateFlow()
    private var api: MihomoApi? = null

    val name: StateFlow<String> =
        combine(tunnelRepository.userTunnelsFlow, _state) { tunnels, state ->
                tunnels.firstOrNull { it.id == state.tunnelId }?.name.orEmpty()
            }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    init {
        viewModelScope.launch {
            runner.state
                .map { (it as? MihomoState.Running)?.let { r -> r.tunnelId to r.controller } }
                .distinctUntilChanged()
                .collect { running ->
                    api = running?.let { MihomoApi(it.second) }
                    _state.update {
                        ClashControlsState(
                            tunnelId = running?.first,
                            expanded = it.expanded,
                            query = it.query,
                            sortByDelay = it.sortByDelay,
                        )
                    }
                    if (running != null) refresh()
                }
        }
    }

    fun refresh() {
        val api = api ?: return
        viewModelScope.launch {
            runCatching { api.proxies() to api.mode() }
                .onSuccess { (proxies, mode) ->
                    _state.update { it.copy(proxies = proxies, mode = mode, error = null) }
                }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }
        }
    }

    fun toggle(group: String) = _state.update {
        it.copy(expanded = if (group in it.expanded) it.expanded - group else it.expanded + group)
    }

    fun select(group: String, proxy: String) {
        val api = api ?: return
        viewModelScope.launch {
            runCatching { api.select(group, proxy) }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }
            refresh()
        }
    }

    /** URL-tests every node of every group at once (each node once, however many groups it's in). */
    fun testAll() {
        val api = api ?: return
        val proxies = _state.value.proxies ?: return
        if (_state.value.testing) return
        val nodes =
            proxies.groups
                .filter { !it.hidden }
                .flatMap { it.all }
                .distinct()
                .mapNotNull { proxies.byName[it] }
                .filter { !it.isGroup && it.type.lowercase() !in UNTESTABLE }
                .map { it.name }
        if (nodes.isEmpty()) return
        _state.update {
            it.copy(testing = true, results = emptyMap(), tested = 0, toTest = nodes.size)
        }
        viewModelScope.launch {
            val permits = Semaphore(PARALLEL_TESTS)
            nodes
                .map { node ->
                    async {
                        permits.withPermit {
                            val delay = runCatching { api.delay(node) }.getOrNull()
                            _state.update {
                                it.copy(results = it.results + (node to delay), tested = it.tested + 1)
                            }
                        }
                    }
                }
                .awaitAll()
            _state.update { it.copy(testing = false) }
            refresh()
        }
    }

    fun setMode(mode: MihomoMode) {
        val api = api ?: return
        viewModelScope.launch {
            runCatching { api.setMode(mode) }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }
            refresh()
        }
    }

    fun setQuery(query: String) = _state.update { it.copy(query = query) }

    fun toggleSort() = _state.update { it.copy(sortByDelay = !it.sortByDelay) }

    private companion object {
        const val PARALLEL_TESTS = 16
        /** Built-in outbounds with nothing to test. */
        val UNTESTABLE = setOf("direct", "reject", "rejectdrop", "pass", "compatible", "dns")
    }
}
