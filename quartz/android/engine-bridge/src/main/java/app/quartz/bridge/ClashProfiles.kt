package app.quartz.bridge

import android.content.Context
import app.quartz.core.data.EngineProfile
import app.quartz.core.data.EngineProfileDao
import com.wgtunnel.parser.Config
import com.wgtunnel.parser.crypto.Key
import com.zaneschepke.wireguardautotunnel.domain.model.TunnelConfig
import com.zaneschepke.wireguardautotunnel.domain.repository.TunnelRepository
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Clash (mihomo) profiles saved as tunnels: an upstream shell row (name, position, group,
 * auto-tunnel rules all work as for any tunnel) plus an [EngineProfile] row and the YAML file.
 */
class ClashProfiles(
    context: Context,
    private val profileDao: EngineProfileDao,
    private val tunnelRepository: TunnelRepository,
    private val ioDispatcher: CoroutineDispatcher,
) {
    val directory = File(context.filesDir, "clash")

    fun file(profile: EngineProfile) = File(directory, profile.profileFile)

    suspend fun get(tunnelId: Int): EngineProfile? = profileDao.get(tunnelId)

    /** Saves [yaml] as a new tunnel named [name] (made unique) and returns its ID. */
    suspend fun create(
        name: String,
        yaml: String,
        groupId: Int? = null,
        subscription: EngineProfile? = null,
    ): Int =
        withContext(ioDispatcher) {
            directory.mkdirs()
            val fileName = UUID.randomUUID().toString() + ".yaml"
            File(directory, fileName).writeText(yaml)
            val names = tunnelRepository.getAll().map { it.name }.toSet()
            val unique =
                if (name !in names) name
                else generateSequence(1) { it + 1 }.map { "$name ($it)" }.first { it !in names }
            tunnelRepository.save(
                TunnelConfig(
                    name = unique,
                    quickConfig = EnginePlaceholder.config(EngineProfile.ENGINE_MIHOMO),
                    groupId = groupId,
                )
            )
            val id =
                tunnelRepository.findByTunnelName(unique)?.id
                    ?: error("Saved tunnel $unique not found")
            profileDao.upsert(
                (subscription ?: EngineProfile(id, EngineProfile.ENGINE_MIHOMO, fileName)).copy(
                    tunnelId = id,
                    engine = EngineProfile.ENGINE_MIHOMO,
                    profileFile = fileName,
                )
            )
            id
        }

    suspend fun readYaml(tunnelId: Int): String? =
        withContext(ioDispatcher) {
            profileDao.get(tunnelId)?.let { file(it).takeIf(File::exists)?.readText() }
        }

    suspend fun writeYaml(tunnelId: Int, yaml: String) =
        withContext(ioDispatcher) {
            val profile = profileDao.get(tunnelId) ?: return@withContext
            val target = file(profile)
            val temp = File(directory, target.name + ".tmp").apply { writeText(yaml) }
            if (!temp.renameTo(target)) {
                target.writeText(yaml)
                temp.delete()
            }
        }

    /** Drops rows and files of tunnels that no longer exist upstream. */
    suspend fun removeOrphans(existingIds: Set<Int>) =
        withContext(ioDispatcher) {
            val orphans =
                profileDao.getAll().filter {
                    it.engine == EngineProfile.ENGINE_MIHOMO && it.tunnelId !in existingIds
                }
            if (orphans.isEmpty()) return@withContext
            orphans.forEach { file(it).delete() }
            profileDao.deleteAll(orphans.map { it.tunnelId })
        }
}

/**
 * The WireGuard text an engine tunnel's shell row carries, so upstream code that parses
 * `quickConfig` keeps working. It never connects: Quartz routes the tunnel to its engine by ID.
 */
object EnginePlaceholder {
    private const val MARKER = "# WGT-Engine = "

    fun config(engine: String): String {
        val key = Key.generatePrivateKey()
        return """
            [Interface]
            $MARKER$engine
            # Quartz: placeholder, never connected. The real profile is managed by Quartz.
            PrivateKey = ${key.toBase64()}
            Address = 10.255.255.1/32

            [Peer]
            PublicKey = ${Key.generatePublicKey(key).toBase64()}
            AllowedIPs = 0.0.0.0/0, ::/0
            Endpoint = 127.0.0.1:9
        """
            .trimIndent() + "\n"
    }

    fun engineOf(quickConfig: String): String? =
        quickConfig
            .lineSequence()
            .firstOrNull { it.trim().startsWith(MARKER) }
            ?.trim()
            ?.removePrefix(MARKER)
            ?.trim()

    /** Whether upstream's parser accepts the placeholder (checked by a unit test). */
    internal fun parses(engine: String): Boolean = runCatching {
        Config.parseQuickString(config(engine))
    }
        .isSuccess
}
