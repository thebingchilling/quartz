package app.quartz.clash.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.quartz.bridge.ClashProfiles
import app.quartz.bridge.MihomoRunner
import app.quartz.bridge.MihomoState
import app.quartz.bridge.Subscriptions
import app.quartz.core.data.EngineProfile
import app.quartz.core.data.EngineProfileDao
import com.dokar.sonner.ToastType
import com.zaneschepke.wireguardautotunnel.R
import com.zaneschepke.wireguardautotunnel.core.orchestration.TunnelCoordinator
import com.zaneschepke.wireguardautotunnel.domain.repository.GlobalEffectRepository
import com.zaneschepke.wireguardautotunnel.domain.repository.TunnelRepository
import com.zaneschepke.wireguardautotunnel.domain.sideeffect.GlobalSideEffect
import com.zaneschepke.wireguardautotunnel.util.FileUtils
import com.zaneschepke.wireguardautotunnel.util.StringValue
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ClashUiState(
    val running: Boolean = false,
    val updatingSubscription: Boolean = false,
)

/**
 * The Clash tunnel screen (FORK.md §8): subscription, settings and export. The live controls
 * (groups and nodes) are listed under the tunnel on the Tunnels screen ([QuartzClashRows]).
 */
class ClashViewModel(
    private val tunnelId: Int,
    private val profiles: ClashProfiles,
    profileDao: EngineProfileDao,
    private val runner: MihomoRunner,
    private val subscriptions: Subscriptions,
    tunnelRepository: TunnelRepository,
    private val tunnelCoordinator: TunnelCoordinator,
    private val globalEffectRepository: GlobalEffectRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(ClashUiState())
    val state: StateFlow<ClashUiState> = _state.asStateFlow()

    val name: StateFlow<String> =
        tunnelRepository.userTunnelsFlow
            .map { list -> list.firstOrNull { it.id == tunnelId }?.name.orEmpty() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    val profile: StateFlow<EngineProfile?> =
        profileDao
            .flow()
            .map { list -> list.firstOrNull { it.tunnelId == tunnelId } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch {
            runner.state
                .map { (it as? MihomoState.Running)?.tunnelId == tunnelId }
                .distinctUntilChanged()
                .collect { running -> _state.update { it.copy(running = running) } }
        }
    }

    fun updateSubscription() {
        _state.update { it.copy(updatingSubscription = true) }
        viewModelScope.launch {
            val result = subscriptions.update(tunnelId)
            _state.update { it.copy(updatingSubscription = false) }
            result
                .onSuccess {
                    if (_state.value.running) tunnelCoordinator.restartActiveTunnels()
                    globalEffectRepository.post(
                        GlobalSideEffect.Snackbar(
                            StringValue.StringResource(R.string.quartz_clash_updated),
                            ToastType.Success,
                        )
                    )
                }
                .onFailure { e ->
                    globalEffectRepository.post(
                        GlobalSideEffect.Snackbar(
                            StringValue.DynamicString(e.message ?: "Update failed"),
                            ToastType.Error,
                        )
                    )
                }
        }
    }

    fun exportProfile() {
        viewModelScope.launch {
            val yaml = profiles.readYaml(tunnelId) ?: return@launch
            globalEffectRepository.post(
                GlobalSideEffect.ExportFile(
                    uri = null,
                    fileName = "${name.value.ifBlank { "clash" }}.yaml",
                    mimeType = FileUtils.ALL_FILE_TYPES,
                    successMessage = StringValue.StringResource(R.string.export_success),
                    prepareFile = { file ->
                        file.writeText(yaml)
                        if (file.length() == 0L) throw IOException("Failed to create profile file")
                    },
                )
            )
        }
    }
}
