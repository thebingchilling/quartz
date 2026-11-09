package app.quartz.clash.ui

import app.quartz.bridge.MihomoRunner
import app.quartz.bridge.MihomoState
import app.quartz.engine.mihomo.MihomoApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * URL-tests every node of the running Clash tunnel (each node once, however many groups it's
 * in). Run by the Tunnels screen's delay test button; results show in the Clash groups.
 */
class ClashDelays(private val runner: MihomoRunner, private val scope: CoroutineScope) {
    data class State(val results: Map<String, Int?> = emptyMap(), val testing: Boolean = false)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        // Results belong to one run of one profile.
        scope.launch {
            runner.state
                .map { (it as? MihomoState.Running)?.controller }
                .distinctUntilChanged()
                .collect { _state.value = State() }
        }
    }

    fun testAll() {
        val controller = (runner.state.value as? MihomoState.Running)?.controller ?: return
        if (_state.value.testing) return
        _state.update { State(testing = true) }
        scope.launch {
            val api = MihomoApi(controller)
            runCatching {
                val proxies = api.proxies()
                val nodes =
                    proxies.groups
                        .filter { !it.hidden }
                        .flatMap { it.all }
                        .distinct()
                        .mapNotNull { proxies.byName[it] }
                        .filter { !it.isGroup && it.type.lowercase() !in UNTESTABLE }
                        .map { it.name }
                val permits = Semaphore(PARALLEL_TESTS)
                nodes
                    .map { node ->
                        async {
                            permits.withPermit {
                                val delay = runCatching { api.delay(node) }.getOrNull()
                                _state.update { it.copy(results = it.results + (node to delay)) }
                            }
                        }
                    }
                    .awaitAll()
            }
            _state.update { it.copy(testing = false) }
        }
    }

    private companion object {
        const val PARALLEL_TESTS = 16
        /** Built-in outbounds with nothing to test. */
        val UNTESTABLE = setOf("direct", "reject", "rejectdrop", "pass", "compatible", "dns")
    }
}
