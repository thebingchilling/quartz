package app.quartz.apprules

import android.content.Context
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber

/** Where an app lives on the device: the main profile or one of its other profiles. */
@Serializable
enum class AppProfile {
    MAIN,
    WORK,
    PRIVATE,
    SECURE_FOLDER,
    DUAL,
    OTHER,
}

/** One app a rule reacts to. [user] is the Android user (profile) ID it's installed in. */
@Serializable
data class AppRef(
    val packageName: String,
    val label: String,
    val profile: AppProfile = AppProfile.MAIN,
    val user: Int = 0,
)

/** While any of [apps] is on screen, auto-tunnel connects [tunnelId] (FORK.md §14). */
@Serializable
data class AppRule(
    val id: String = UUID.randomUUID().toString(),
    val tunnelId: Int,
    val apps: List<AppRef> = emptyList(),
)

@Serializable
data class AppRulesData(
    val enabled: Boolean = true,
    val rules: List<AppRule> = emptyList(),
    /**
     * Apps seen on screen that the app list can't show: private space (hidden from apps that
     * aren't the launcher) and Secure Folder. Opening one once makes it selectable.
     */
    val seen: List<AppRef> = emptyList(),
) {
    /** The rule for an app now on screen. Detection gives only the package, not its profile. */
    fun ruleFor(packageName: String?): AppRule? {
        if (!enabled || packageName == null) return null
        return rules.firstOrNull { rule -> rule.apps.any { it.packageName == packageName } }
    }
}

/**
 * Auto-tunnel's app rules (FORK.md §14), kept in `files/app_rules.json` (and in backups, as
 * `quartz/app_rules.json`).
 */
class AppRules(context: Context, private val ioDispatcher: CoroutineDispatcher) {
    private val file = File(context.filesDir, FILE)
    private val mutex = Mutex()
    private val _data = MutableStateFlow(load())
    val data: StateFlow<AppRulesData> = _data.asStateFlow()

    private fun load(): AppRulesData =
        runCatching { if (file.isFile) JSON.decodeFromString<AppRulesData>(file.readText()) else null }
            .onFailure { Timber.e(it, "Unreadable app rules") }
            .getOrNull() ?: AppRulesData()

    suspend fun update(transform: (AppRulesData) -> AppRulesData) =
        mutex.withLock {
            val next = transform(_data.value)
            if (next == _data.value) return@withLock
            _data.value = next
            withContext(ioDispatcher) { write(next) }
        }

    suspend fun setEnabled(enabled: Boolean) = update { it.copy(enabled = enabled) }

    suspend fun save(rule: AppRule) = update { data ->
        val rules =
            if (data.rules.any { it.id == rule.id })
                data.rules.map { if (it.id == rule.id) rule else it }
            else data.rules + rule
        data.copy(rules = rules)
    }

    suspend fun delete(ruleId: String) = update { data ->
        data.copy(rules = data.rules.filterNot { it.id == ruleId })
    }

    /** Remembers an app seen on screen that the app list can't show (see [AppRulesData.seen]). */
    fun remember(app: AppRef) {
        val current = _data.value
        if (current.seen.any { it.packageName == app.packageName }) return
        _data.update { it.copy(seen = (listOf(app) + it.seen).take(MAX_SEEN)) }
        runCatching { write(_data.value) }.onFailure { Timber.e(it, "Couldn't save seen apps") }
    }

    /** Rules whose tunnel was deleted are dropped. */
    suspend fun removeTunnelsNotIn(existing: Set<Int>) = update { data ->
        data.copy(rules = data.rules.filter { it.tunnelId in existing })
    }

    fun export(): ByteArray = JSON.encodeToString(_data.value).encodeToByteArray()

    suspend fun restore(bytes: ByteArray?) = update {
        bytes?.let { runCatching { JSON.decodeFromString<AppRulesData>(it.decodeToString()) }.getOrNull() }
            ?: AppRulesData()
    }

    @Synchronized
    private fun write(data: AppRulesData) {
        val temp = File(file.parentFile, "$FILE.tmp")
        temp.writeText(JSON.encodeToString(data))
        if (!temp.renameTo(file)) {
            file.writeText(temp.readText())
            temp.delete()
        }
    }

    companion object {
        const val FILE = "app_rules.json"
        private const val MAX_SEEN = 50
        private val JSON = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
