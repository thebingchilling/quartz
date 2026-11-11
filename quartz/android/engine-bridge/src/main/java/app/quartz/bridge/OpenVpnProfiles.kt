package app.quartz.bridge

import android.content.Context
import app.quartz.core.data.EngineProfile
import app.quartz.core.data.EngineProfileDao
import com.zaneschepke.wireguardautotunnel.domain.model.TunnelConfig
import com.zaneschepke.wireguardautotunnel.domain.repository.TunnelRepository
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** A username and password for an OpenVPN profile that asks for them (`auth-user-pass`). */
data class OpenVpnCredentials(val username: String, val password: String)

/**
 * OpenVPN profiles saved as tunnels, like [ClashProfiles]: an upstream shell row plus an
 * [EngineProfile] row and the `.ovpn` text in `files/openvpn/`. Credentials the user enters are
 * kept in the same file as an inline `<auth-user-pass>` block (OpenVPN 2.6's own syntax), so they
 * travel with backups; it's stripped before the profile reaches openvpn3.
 */
class OpenVpnProfiles(
    context: Context,
    private val profileDao: EngineProfileDao,
    private val tunnelRepository: TunnelRepository,
    private val ioDispatcher: CoroutineDispatcher,
) {
    val directory = File(context.filesDir, "openvpn")

    fun file(profile: EngineProfile) = File(directory, profile.profileFile)

    /** Saves [ovpn] as a new tunnel named [name] (made unique) and returns its ID. */
    suspend fun create(name: String, ovpn: String, groupId: Int? = null): Int =
        withContext(ioDispatcher) {
            directory.mkdirs()
            val fileName = UUID.randomUUID().toString() + ".ovpn"
            File(directory, fileName).writeText(ovpn.removePrefix("﻿"))
            val names = tunnelRepository.getAll().map { it.name }.toSet()
            val unique =
                if (name !in names) name
                else generateSequence(1) { it + 1 }.map { "$name ($it)" }.first { it !in names }
            tunnelRepository.save(
                TunnelConfig(
                    name = unique,
                    quickConfig = EnginePlaceholder.config(EngineProfile.ENGINE_OPENVPN),
                    groupId = groupId,
                )
            )
            val id =
                tunnelRepository.findByTunnelName(unique)?.id
                    ?: error("Saved tunnel $unique not found")
            profileDao.upsert(EngineProfile(id, EngineProfile.ENGINE_OPENVPN, fileName))
            id
        }

    /** The profile for openvpn3 (without Quartz's credentials block) and the credentials. */
    suspend fun read(tunnelId: Int): Pair<String, OpenVpnCredentials?>? =
        withContext(ioDispatcher) {
            val profile = profileDao.get(tunnelId) ?: return@withContext null
            val text = file(profile).takeIf(File::exists)?.readText() ?: return@withContext null
            split(text)
        }

    suspend fun credentials(tunnelId: Int): OpenVpnCredentials? = read(tunnelId)?.second

    /** Saves (or with null, removes) the username and password kept with the profile. */
    suspend fun saveCredentials(tunnelId: Int, credentials: OpenVpnCredentials?) =
        withContext(ioDispatcher) {
            val profile = profileDao.get(tunnelId) ?: return@withContext
            val target = file(profile)
            val (bare, _) = split(target.takeIf(File::exists)?.readText() ?: return@withContext)
            val text =
                if (credentials == null || credentials.username.isBlank()) bare
                else
                    bare.trimEnd() +
                        "\n$OPEN\n${credentials.username}\n${credentials.password}\n$CLOSE\n"
            val temp = File(directory, target.name + ".tmp").apply { writeText(text) }
            if (!temp.renameTo(target)) {
                target.writeText(text)
                temp.delete()
            }
        }

    /** Drops rows and files of tunnels that no longer exist upstream. */
    suspend fun removeOrphans(existingIds: Set<Int>) =
        withContext(ioDispatcher) {
            val orphans =
                profileDao.getAll().filter {
                    it.engine == EngineProfile.ENGINE_OPENVPN && it.tunnelId !in existingIds
                }
            if (orphans.isEmpty()) return@withContext
            orphans.forEach { file(it).delete() }
            profileDao.deleteAll(orphans.map { it.tunnelId })
        }

    companion object {
        private const val OPEN = "<auth-user-pass>"
        private const val CLOSE = "</auth-user-pass>"
        private val INLINE = Regex("(?s)<auth-user-pass>\\s*\\n(.*?)</auth-user-pass>\\s*")

        /**
         * Splits an inline `<auth-user-pass>` block off a profile. The profile keeps a bare
         * `auth-user-pass` line, so openvpn3 still asks for the credentials it's then given.
         */
        fun split(text: String): Pair<String, OpenVpnCredentials?> {
            val match = INLINE.find(text) ?: return text to null
            val lines = match.groupValues[1].lines().map { it.trim() }.filter { it.isNotEmpty() }
            var bare = text.removeRange(match.range)
            if (!Regex("(?im)^\\s*auth-user-pass\\b").containsMatchIn(bare)) {
                bare = bare.trimEnd() + "\nauth-user-pass\n"
            }
            val credentials =
                lines.getOrNull(0)?.let { OpenVpnCredentials(it, lines.getOrElse(1) { "" }) }
            return bare to credentials
        }
    }
}
