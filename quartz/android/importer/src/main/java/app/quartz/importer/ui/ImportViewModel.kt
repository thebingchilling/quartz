package app.quartz.importer.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.quartz.bridge.ClashProfiles
import app.quartz.bridge.Subscriptions
import app.quartz.bridge.Subscriptions.Companion.withInfo
import app.quartz.core.data.EngineProfile
import app.quartz.importer.ClashTemplate
import app.quartz.importer.Detected
import app.quartz.importer.ImportDetector
import app.quartz.importer.ProxyNode
import app.quartz.importer.SubscriptionInfo
import com.dokar.sonner.ToastType
import com.zaneschepke.wireguardautotunnel.R
import com.zaneschepke.wireguardautotunnel.domain.model.TunnelGroup
import com.zaneschepke.wireguardautotunnel.domain.repository.GlobalEffectRepository
import com.zaneschepke.wireguardautotunnel.domain.repository.TunnelGroupRepository
import com.zaneschepke.wireguardautotunnel.domain.sideeffect.GlobalSideEffect
import com.zaneschepke.wireguardautotunnel.util.StringValue
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class TemplateChoice {
    SIMPLE,
    RULE_BASED,
    CUSTOM,
}

data class ImportUiState(
    val input: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    val detected: Detected? = null,
    val subscriptionUrl: String? = null,
    val info: SubscriptionInfo? = null,
    val name: String = "",
    val template: TemplateChoice = TemplateChoice.SIMPLE,
    val country: String = Subscriptions.defaultCountry(),
    val customTemplate: String = "",
    val groupId: Int? = null,
    val updateIntervalHours: String = "24",
    val wifiOnly: Boolean = false,
    val userAgent: String = SubscriptionInfo.USER_AGENT,
    /** mihomo's complaint about the profile; saving needs a second tap. */
    val validationWarning: String? = null,
    /** Set once saved: the new Clash tunnel's ID, or -1 after a WireGuard import. */
    val savedTunnelId: Int? = null,
)

/** One Import entry for everything (FORK.md §7): detect, preview, save. */
class ImportViewModel(
    private val context: Context,
    private val subscriptions: Subscriptions,
    private val profiles: ClashProfiles,
    groupRepository: TunnelGroupRepository,
    private val globalEffectRepository: GlobalEffectRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(ImportUiState())
    val state: StateFlow<ImportUiState> = _state.asStateFlow()

    val groups: StateFlow<List<TunnelGroup>> =
        groupRepository.flow.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            emptyList(),
        )

    fun update(transform: (ImportUiState) -> ImportUiState) = _state.update {
        transform(it).copy(validationWarning = null, error = null)
    }

    fun reset() = _state.update { ImportUiState(input = it.input) }

    fun detect(text: String = _state.value.input, fileName: String? = null) {
        _state.update {
            it.copy(
                input = text,
                busy = true,
                error = null,
                detected = null,
                info = null,
                subscriptionUrl = null,
            )
        }
        viewModelScope.launch {
            runCatching {
                when (val detected = ImportDetector.detect(text, fileName)) {
                    is Detected.SubscriptionUrl -> {
                        val fetched = subscriptions.fetch(detected.url, _state.value.userAgent)
                        _state.update {
                            it.copy(
                                detected = fetched.detected,
                                info = fetched.info,
                                subscriptionUrl = detected.url,
                                name = fetched.info.name ?: hostOf(detected.url),
                                updateIntervalHours =
                                    (fetched.info.updateIntervalHours ?: 24).toString(),
                            )
                        }
                    }
                    else ->
                        _state.update {
                            it.copy(
                                detected = detected,
                                name = fileName?.substringBeforeLast('.') ?: defaultName(detected),
                            )
                        }
                }
            }
                .onFailure { e -> _state.update { it.copy(error = e.message ?: e.toString()) } }
            _state.update { it.copy(busy = false) }
        }
    }

    fun detectFile(uri: Uri) {
        viewModelScope.launch {
            val (name, text) =
                withContext(Dispatchers.IO) {
                    runCatching {
                        val name =
                            context.contentResolver
                                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                                ?.use { if (it.moveToFirst()) it.getString(0) else null }
                        name to
                            context.contentResolver.openInputStream(uri)!!.use {
                                it.readBytes().decodeToString()
                            }
                    }
                        .getOrElse { null to null }
                }
            if (text == null) _state.update { it.copy(error = "Couldn't read the file") }
            else detect(text, name)
        }
    }

    /** Saves the detected profile as a Clash tunnel; WireGuard goes through upstream's import. */
    fun save(force: Boolean = false) {
        val s = _state.value
        val detected = s.detected ?: return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            runCatching {
                val template =
                    when (s.template) {
                        TemplateChoice.SIMPLE -> ClashTemplate.Simple
                        TemplateChoice.RULE_BASED -> ClashTemplate.RuleBased(s.country.trim())
                        TemplateChoice.CUSTOM -> ClashTemplate.Custom(s.customTemplate)
                    }
                val yaml = Subscriptions.profileYaml(detected, template)
                if (!force) {
                    subscriptions.validate(yaml)?.let { warning ->
                        _state.update { it.copy(validationWarning = warning) }
                        return@launch
                    }
                }
                val subscription =
                    s.subscriptionUrl?.let { url ->
                        EngineProfile(
                                tunnelId = 0,
                                engine = EngineProfile.ENGINE_MIHOMO,
                                profileFile = "",
                                subscriptionUrl = url,
                                userAgent = s.userAgent.ifBlank { null },
                                template = Subscriptions.templateKey(template),
                                customTemplate =
                                    s.customTemplate.takeIf { s.template == TemplateChoice.CUSTOM },
                                updateIntervalHours = s.updateIntervalHours.toIntOrNull(),
                                wifiOnly = s.wifiOnly,
                                lastUpdatedAt = System.currentTimeMillis(),
                            )
                            .withInfo(s.info ?: SubscriptionInfo())
                    }
                val id = profiles.create(s.name.ifBlank { "Clash" }, yaml, s.groupId, subscription)
                globalEffectRepository.post(
                    GlobalSideEffect.Snackbar(
                        StringValue.StringResource(R.string.quartz_import_saved, s.name),
                        ToastType.Success,
                    )
                )
                _state.update { it.copy(savedTunnelId = id) }
            }
                .onFailure { e -> _state.update { it.copy(error = e.message ?: e.toString()) } }
            _state.update { it.copy(busy = false) }
        }
    }

    fun markWireGuardImported() = _state.update { it.copy(savedTunnelId = -1) }

    private fun defaultName(detected: Detected) =
        when (detected) {
            is Detected.ProxyList ->
                if (detected.proxies.size == 1) detected.proxies[0]["name"].toString() else "Clash"
            else -> "Clash"
        }

    private fun hostOf(url: String) =
        runCatching { URI(url).host }.getOrNull()?.removePrefix("www.") ?: "Subscription"

    companion object {
        /** A wireguard:// share link as a WireGuard config, for "Add as WireGuard tunnel". */
        fun wireGuardConfig(node: ProxyNode): String = buildString {
            appendLine("[Interface]")
            appendLine("PrivateKey = ${node["private-key"]}")
            val addresses =
                listOfNotNull(node["ip"]?.let { "$it/32" }, node["ipv6"]?.let { "$it/128" })
            if (addresses.isNotEmpty()) appendLine("Address = ${addresses.joinToString(", ")}")
            node["mtu"]?.let { appendLine("MTU = $it") }
            appendLine()
            appendLine("[Peer]")
            appendLine("PublicKey = ${node["public-key"]}")
            node["pre-shared-key"]?.let { appendLine("PresharedKey = $it") }
            appendLine("AllowedIPs = 0.0.0.0/0, ::/0")
            val server = node["server"].toString()
            appendLine("Endpoint = ${if (':' in server) "[$server]" else server}:${node["port"]}")
        }
    }
}
