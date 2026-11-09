package app.quartz.warp.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.quartz.warp.EndpointChoice
import app.quartz.warp.GeneratedWarp
import app.quartz.warp.Junk
import app.quartz.warp.SlotTunnel
import app.quartz.warp.WarpConfigBuilder
import app.quartz.warp.WarpEndpoints
import app.quartz.warp.WarpOptions
import app.quartz.warp.WarpRepository
import app.quartz.warp.WarpVariant
import com.dokar.sonner.ToastType
import com.zaneschepke.wireguardautotunnel.R
import com.zaneschepke.wireguardautotunnel.domain.repository.GlobalEffectRepository
import com.zaneschepke.wireguardautotunnel.domain.sideeffect.GlobalSideEffect
import com.zaneschepke.wireguardautotunnel.util.FileUtils
import com.zaneschepke.wireguardautotunnel.util.StringValue
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The site configurator's fields, kept as text while editing. */
data class WarpForm(
    val variant: WarpVariant = WarpVariant.WG,
    val endpoint: String = "",
    val dns: String = WarpOptions.DEFAULT_DNS,
    val mtu: String = WarpOptions.DEFAULT_MTU.toString(),
    val allowedIps: String = WarpOptions.DEFAULT_ALLOWED_IPS,
    val keepalive: String = "0",
    val deviceType: String = WarpOptions.DEFAULT_DEVICE_TYPE,
    val locale: String = WarpOptions.DEFAULT_LOCALE,
    val jc: String = Junk.DEFAULT.jc.toString(),
    val jMin: String = Junk.DEFAULT.jMin.toString(),
    val jMax: String = Junk.DEFAULT.jMax.toString(),
) {
    /** Like the site: blank or invalid fields fall back to their defaults, never an error. */
    fun toOptions() =
        WarpOptions(
            variant = variant,
            junk = Junk(int(jc, 4), int(jMin, 40), int(jMax, 70)),
            // Blank is Cloudflare's default endpoint; the dice fills in a random one.
            endpoint =
                endpoint.trim().takeIf { it.isNotEmpty() }?.let { EndpointChoice.Custom(it) }
                    ?: EndpointChoice.Default,
            mtu = int(mtu, WarpOptions.DEFAULT_MTU),
            dns = dns.trim(),
            allowedIps = allowedIps.trim().ifEmpty { WarpOptions.DEFAULT_ALLOWED_IPS },
            keepalive = keepalive.trim().toIntOrNull()?.takeIf { it > 0 },
            deviceType = deviceType.trim().ifEmpty { WarpOptions.DEFAULT_DEVICE_TYPE },
            locale = locale.trim().ifEmpty { WarpOptions.DEFAULT_LOCALE },
        )

    private fun int(value: String, fallback: Int) =
        value.trim().toIntOrNull()?.takeIf { it >= 0 } ?: fallback
}

data class WarpUiState(
    val form: WarpForm = WarpForm(),
    val busy: Boolean = false,
    /** This visit's registration; options rebuild its config without registering again. */
    val generated: GeneratedWarp? = null,
)

class WarpViewModel(
    private val repository: WarpRepository,
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

    fun update(transform: (WarpForm) -> WarpForm) = _state.update {
        it.copy(form = transform(it.form))
    }

    fun reroll() = update { it.copy(endpoint = WarpEndpoints.random()) }

    /** Registers a new device (a failed attempt keeps the previous config, as on the site). */
    fun generate() {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            runCatching { repository.generate(_state.value.form.toOptions()) }
                .onSuccess { generated ->
                    _state.update { it.copy(busy = false, generated = generated) }
                }
                .onFailure { e ->
                    _state.update { it.copy(busy = false) }
                    showError(e)
                }
        }
    }

    fun save() {
        val generated = _state.value.generated ?: return
        if (_state.value.busy) return
        val options = _state.value.form.toOptions()
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            runCatching { repository.save(generated, options) }
                .onSuccess {
                    // Saved: back to Generate for the next one.
                    _state.update { it.copy(busy = false, generated = null) }
                    val message =
                        StringValue.StringResource(
                            R.string.quartz_warp_saved_as,
                            tunnelName(options),
                        )
                    globalEffectRepository.post(
                        GlobalSideEffect.Snackbar(message, ToastType.Success)
                    )
                }
                .onFailure { e ->
                    _state.update { it.copy(busy = false) }
                    showError(e)
                }
        }
    }

    private suspend fun showError(e: Throwable) =
        globalEffectRepository.post(
            GlobalSideEffect.Snackbar(
                StringValue.DynamicString(e.message ?: e.toString()),
                ToastType.Error,
            )
        )

    companion object {
        fun tunnelName(options: WarpOptions) =
            WarpConfigBuilder.tunnelName(options.slot, options.variant)
    }
}
