package app.quartz.warp.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.quartz.warp.EndpointChoice
import app.quartz.warp.ExpiryChoice
import app.quartz.warp.GeneratedWarp
import app.quartz.warp.Junk
import app.quartz.warp.JunkPreset
import app.quartz.warp.SlotTunnel
import app.quartz.warp.WarpConfigBuilder
import app.quartz.warp.WarpOptions
import app.quartz.warp.WarpOutput
import app.quartz.warp.WarpRepository
import app.quartz.warp.WarpVariant
import com.dokar.sonner.ToastType
import com.zaneschepke.wireguardautotunnel.R
import com.zaneschepke.wireguardautotunnel.domain.model.TunnelGroup
import com.zaneschepke.wireguardautotunnel.domain.repository.GlobalEffectRepository
import com.zaneschepke.wireguardautotunnel.domain.repository.TunnelGroupRepository
import com.zaneschepke.wireguardautotunnel.domain.sideeffect.GlobalSideEffect
import com.zaneschepke.wireguardautotunnel.util.FileUtils
import com.zaneschepke.wireguardautotunnel.util.StringValue
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class EndpointMode {
    DEFAULT,
    RANDOM,
    CUSTOM,
}

/** The generator form; numbers are kept as text while editing. */
data class WarpForm(
    val slot: String = WarpOptions.DEFAULT_SLOT,
    val output: WarpOutput = WarpOutput.AMNEZIA,
    val junkPreset: JunkPreset = JunkPreset.MEDIUM,
    val jc: String = JunkPreset.MEDIUM.jc.toString(),
    val jMin: String = JunkPreset.MEDIUM.jMin.toString(),
    val jMax: String = JunkPreset.MEDIUM.jMax.toString(),
    val endpointMode: EndpointMode = EndpointMode.DEFAULT,
    val customEndpoint: String = "",
    val expiry: ExpiryChoice = ExpiryChoice.NEVER,
    /** Epoch millis for [ExpiryChoice.CUSTOM]. */
    val customExpiry: Long? = null,
    val mtu: String = WarpOptions.DEFAULT_MTU.toString(),
    val dns: String = WarpOptions.DEFAULT_DNS,
    val allowedIps: String = WarpOptions.DEFAULT_ALLOWED_IPS,
    val keepalive: String = WarpOptions.DEFAULT_KEEPALIVE.toString(),
    val license: String = "",
    val mimicHost: String = "",
    val groupId: Int? = null,
    val deleteOldRegistration: Boolean = true,
    val revokeOnExpiry: Boolean = false,
)

enum class WarpField {
    SLOT,
    JUNK,
    ENDPOINT,
    EXPIRY,
    MTU,
    KEEPALIVE,
    ALLOWED_IPS,
}

data class WarpUiState(
    val form: WarpForm = WarpForm(),
    val errors: Set<WarpField> = emptySet(),
    val busy: Boolean = false,
    val error: String? = null,
    val generated: GeneratedWarp? = null,
)

class WarpViewModel(
    private val repository: WarpRepository,
    groupRepository: TunnelGroupRepository,
    private val globalEffectRepository: GlobalEffectRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(WarpUiState())
    val state: StateFlow<WarpUiState> = _state.asStateFlow()

    val warpTunnels: StateFlow<List<SlotTunnel>> =
        repository.warpTunnels.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            emptyList(),
        )

    val groups: StateFlow<List<TunnelGroup>> =
        groupRepository.flow.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            emptyList(),
        )

    fun update(transform: (WarpForm) -> WarpForm) = _state.update {
        it.copy(form = transform(it.form), errors = emptySet(), error = null)
    }

    fun setJunkPreset(preset: JunkPreset) = update {
        if (preset == JunkPreset.CUSTOM) it.copy(junkPreset = preset)
        else
            it.copy(
                junkPreset = preset,
                jc = preset.jc.toString(),
                jMin = preset.jMin.toString(),
                jMax = preset.jMax.toString(),
            )
    }

    fun generate() {
        val form = _state.value.form
        val (options, errors) = form.toOptions()
        if (options == null) {
            _state.update { it.copy(errors = errors) }
            return
        }
        _state.update { it.copy(busy = true, error = null, generated = null) }
        viewModelScope.launch {
            runCatching { repository.generate(options) }
                .onSuccess { generated ->
                    _state.update { it.copy(busy = false, generated = generated) }
                }
                .onFailure { e ->
                    _state.update { it.copy(busy = false, error = e.message ?: e.toString()) }
                }
        }
    }

    fun save() {
        val generated = _state.value.generated ?: return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            runCatching { repository.save(generated) }
                .onSuccess { result ->
                    _state.update { it.copy(busy = false, generated = null) }
                    val message =
                        if (result.replaced > 0)
                            StringValue.StringResource(
                                R.string.quartz_warp_replaced,
                                result.replaced,
                            )
                        else
                            StringValue.StringResource(
                                R.string.quartz_warp_imported,
                                result.created,
                            )
                    globalEffectRepository.post(
                        GlobalSideEffect.Snackbar(message, ToastType.Success)
                    )
                }
                .onFailure { e ->
                    _state.update { it.copy(busy = false, error = e.message ?: e.toString()) }
                }
        }
    }

    fun discard() = _state.update { it.copy(generated = null) }

    fun export(variant: WarpVariant) {
        val generated = _state.value.generated ?: return
        val config = generated.configs[variant] ?: return
        val name = WarpConfigBuilder.tunnelName(generated.options.slot, variant)
        viewModelScope.launch {
            globalEffectRepository.post(
                GlobalSideEffect.ExportFile(
                    uri = null,
                    fileName = "$name.conf",
                    mimeType = FileUtils.ALL_FILE_TYPES,
                    successMessage = StringValue.StringResource(R.string.export_success),
                    prepareFile = { file ->
                        file.writeText(config)
                        if (file.length() == 0L) throw IOException("Failed to create config file")
                    },
                )
            )
        }
    }

    fun detach(tunnelId: Int) = viewModelScope.launch { repository.detach(tunnelId) }

    private fun WarpForm.toOptions(): Pair<WarpOptions?, Set<WarpField>> {
        val errors = mutableSetOf<WarpField>()
        val slot = slot.trim().lowercase()
        if (!WarpOptions.isValidSlot(slot)) errors += WarpField.SLOT
        val junk = Junk(jc.toIntOrNull() ?: -1, jMin.toIntOrNull() ?: -1, jMax.toIntOrNull() ?: -1)
        if (WarpVariant.AWG in output.variants && !junk.isValid) errors += WarpField.JUNK
        val endpoint =
            when (endpointMode) {
                EndpointMode.DEFAULT -> EndpointChoice.Default
                EndpointMode.RANDOM -> EndpointChoice.Random
                EndpointMode.CUSTOM ->
                    if (ENDPOINT.matches(customEndpoint.trim()))
                        EndpointChoice.Custom(customEndpoint.trim())
                    else EndpointChoice.Default.also { errors += WarpField.ENDPOINT }
            }
        val now = System.currentTimeMillis()
        val expiresAt =
            when (expiry) {
                ExpiryChoice.NEVER -> null
                ExpiryChoice.CUSTOM ->
                    customExpiry
                        ?.takeIf { it > now }
                        .also { if (it == null) errors += WarpField.EXPIRY }
                else -> now + TimeUnit.DAYS.toMillis(expiry.days!!.toLong())
            }
        val mtu =
            mtu.toIntOrNull()
                ?.takeIf { it in 576..1500 }
                .also { if (it == null) errors += WarpField.MTU }
        val keepalive =
            if (keepalive.isBlank()) null
            else
                keepalive
                    .toIntOrNull()
                    ?.takeIf { it in 0..65535 }
                    .also { if (it == null) errors += WarpField.KEEPALIVE }
        if (allowedIps.isBlank()) errors += WarpField.ALLOWED_IPS
        if (errors.isNotEmpty()) return null to errors
        return WarpOptions(
            slot = slot,
            output = output,
            junk = junk,
            endpoint = endpoint,
            mtu = mtu!!,
            dns = dns,
            allowedIps = allowedIps,
            keepalive = keepalive,
            license = license.trim().ifEmpty { null },
            mimicHost = mimicHost.trim().ifEmpty { null },
            expiresAt = expiresAt,
            groupId = groupId,
            deleteOldRegistration = deleteOldRegistration,
            revokeOnExpiry = revokeOnExpiry,
        ) to emptySet()
    }

    private companion object {
        /** host:port, IPv4:port or [IPv6]:port. */
        val ENDPOINT = Regex("^(\\[[0-9a-fA-F:]+]|[A-Za-z0-9.-]+):\\d{1,5}$")
    }
}
