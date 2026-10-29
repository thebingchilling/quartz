package app.quartz.warp

import android.content.Context
import app.quartz.core.data.TunnelMeta
import app.quartz.core.data.TunnelMetaDao
import app.quartz.warp.awg.AwgPayloads
import com.zaneschepke.wireguardautotunnel.core.orchestration.TunnelCoordinator
import com.zaneschepke.wireguardautotunnel.core.tunnel.TunnelProvider
import com.zaneschepke.wireguardautotunnel.domain.model.TunnelConfig
import com.zaneschepke.wireguardautotunnel.domain.repository.TunnelRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

/** Configs made from one registration, not saved yet. */
data class GeneratedWarp(
    val registration: WarpRegistration,
    val options: WarpOptions,
    val endpoint: String,
    val expiresAt: Long?,
    val configs: Map<WarpVariant, String>,
)

/** A saved tunnel that belongs to a WARP slot. */
data class SlotTunnel(val tunnel: TunnelConfig, val meta: TunnelMeta) {
    val variant: WarpVariant?
        get() = WarpVariant.fromTag(meta.warpVariant)
}

data class ImportConflict(
    val importedId: Int,
    val importedName: String,
    val existingId: Int,
    val existingName: String,
    val slot: String,
)

data class SaveResult(val tunnelIds: List<Int>, val replaced: Int, val created: Int)

/**
 * The generator's state: which tunnels sit in which slot, generating, saving into a slot (replacing
 * in place), detaching, and adopting imported files that carry markers. FORK.md §9.
 */
class WarpRepository(
    private val context: Context,
    private val api: WarpApi,
    private val metaDao: TunnelMetaDao,
    private val tunnelRepository: TunnelRepository,
    private val tunnelProvider: TunnelProvider,
    private val tunnelCoordinator: Lazy<TunnelCoordinator>,
    private val ioDispatcher: CoroutineDispatcher,
) {
    // Saving and adopting both read then write slots; never interleave them.
    private val mutex = Mutex()

    private val capturedPayloads by lazy {
        runCatching {
                context.assets.open(AwgPayloads.ASSET).bufferedReader().use {
                    AwgPayloads.parse(it.readText())
                }
            }
            .getOrDefault(emptyList())
    }

    val warpTunnels: Flow<List<SlotTunnel>> =
        combine(metaDao.flow(), tunnelRepository.userTunnelsFlow) { metas, tunnels ->
            val byId = tunnels.associateBy { it.id }
            metas
                .filter { it.source == TunnelMeta.SOURCE_WARP }
                .mapNotNull { meta -> byId[meta.tunnelId]?.let { SlotTunnel(it, meta) } }
                .sortedWith(compareBy({ it.meta.warpSlot }, { it.meta.warpVariant }))
        }

    suspend fun generate(options: WarpOptions): GeneratedWarp =
        withContext(ioDispatcher) {
            val registration = api.register(options.license)
            val expiresAt = WarpConfigBuilder.effectiveExpiry(options.expiresAt, registration.ttlAt)
            val endpoint = WarpConfigBuilder.resolveEndpoint(options.endpoint, registration)
            // I1..I5 are picked once per registration, so "Both" shares them.
            val awg =
                if (WarpVariant.AWG in options.output.variants) {
                    AwgPayloads.pick(
                        capturedPayloads,
                        options.mimicHost?.takeIf { it.isNotBlank() }
                            ?: WarpOptions.MIMIC_HOSTS.random(),
                    )
                } else null
            val configs =
                options.output.variants.associateWith { variant ->
                    WarpConfigBuilder.build(
                        registration,
                        variant,
                        options,
                        endpoint,
                        awg,
                        expiresAt,
                    )
                }
            GeneratedWarp(registration, options, endpoint, expiresAt, configs)
        }

    /**
     * Saves [generated] into its slot: a variant that already has a tunnel there is replaced in
     * place (same ID, so name, position, group, auto-tunnel rules, split tunnelling and shortcuts
     * stay); otherwise a tunnel is created. Reconnects if a replaced tunnel is running.
     */
    suspend fun save(generated: GeneratedWarp): SaveResult {
        val options = generated.options
        val registration = generated.registration
        val staleRegistrations = mutableListOf<TunnelMeta>()
        val ids = mutableListOf<Int>()
        var replaced = 0
        mutex.withLock {
            val tunnels = tunnelRepository.getAll().associateBy { it.id }
            val slotMetas = metaDao.getBySlot(options.slot).filter { it.tunnelId in tunnels }
            for ((variant, config) in generated.configs) {
                val existing = slotMetas.firstOrNull { it.warpVariant == variant.tag }
                val tunnelId =
                    if (existing != null) {
                        tunnelRepository.save(
                            tunnels.getValue(existing.tunnelId).copy(quickConfig = config)
                        )
                        if (existing.warpRegId != null && existing.warpRegId != registration.id)
                            staleRegistrations += existing
                        replaced++
                        existing.tunnelId
                    } else {
                        val names = tunnelRepository.getAll().map { it.name }.toSet()
                        val name =
                            uniqueName(WarpConfigBuilder.tunnelName(options.slot, variant), names)
                        tunnelRepository.save(
                            TunnelConfig(
                                name = name,
                                quickConfig = config,
                                groupId = options.groupId,
                            )
                        )
                        tunnelRepository.findByTunnelName(name)?.id
                            ?: error("Saved tunnel $name not found")
                    }
                metaDao.upsert(
                    TunnelMeta(
                        tunnelId = tunnelId,
                        source = TunnelMeta.SOURCE_WARP,
                        warpSlot = options.slot,
                        warpVariant = variant.tag,
                        expiresAt = generated.expiresAt,
                        warpRegId = registration.id,
                        warpToken = registration.token,
                        warpApiVersion = registration.apiVersion,
                        revokeOnExpiry = options.revokeOnExpiry,
                    )
                )
                ids += tunnelId
            }
        }

        if (ids.any { it in tunnelProvider.backendStatus.value.activeTunnels }) {
            tunnelCoordinator.value.restartActiveTunnels()
        }
        if (options.deleteOldRegistration) {
            staleRegistrations
                .distinctBy { it.warpRegId }
                .forEach { deleteRegistrationIfUnused(it) }
        }
        return SaveResult(ids, replaced = replaced, created = ids.size - replaced)
    }

    /** Removes the generator tag: regenerate no longer touches this tunnel. */
    suspend fun detach(tunnelId: Int) = mutex.withLock {
        tunnelRepository.getById(tunnelId)?.let { tunnel ->
            tunnelRepository.save(tunnel.copy(quickConfig = WarpMarkers.strip(tunnel.quickConfig)))
        }
        metaDao.delete(tunnelId)
    }

    /** Deletes [meta]'s registration with Cloudflare unless another tunnel still uses it. */
    suspend fun deleteRegistrationIfUnused(meta: TunnelMeta) {
        val regId = meta.warpRegId ?: return
        val token = meta.warpToken ?: return
        if (metaDao.getAll().any { it.warpRegId == regId && it.tunnelId != meta.tunnelId }) return
        runCatching {
            api.delete(
                regId,
                token,
                meta.warpApiVersion ?: CloudflareWarpApi.API_VERSIONS.first(),
            )
        }
            .onFailure { Timber.w(it, "Could not delete WARP registration") }
    }

    suspend fun revoke(meta: TunnelMeta) {
        val regId = meta.warpRegId ?: return
        val token = meta.warpToken ?: return
        runCatching {
            api.delete(
                regId,
                token,
                meta.warpApiVersion ?: CloudflareWarpApi.API_VERSIONS.first(),
            )
        }
            .onSuccess {
                metaDao
                    .getAll()
                    .filter { it.warpRegId == regId }
                    .forEach { metaDao.upsert(it.copy(warpRegId = null, warpToken = null)) }
            }
            .onFailure { Timber.w(it, "Could not revoke WARP registration") }
    }

    /** Imported WARP files whose slot and variant are already taken, waiting for the user. */
    val importConflicts: StateFlow<List<ImportConflict>>
        get() = _importConflicts.asStateFlow()

    private val _importConflicts = MutableStateFlow<List<ImportConflict>>(emptyList())

    /**
     * Keeps tunnel_meta in step with upstream's tunnels: drops rows whose tunnel is gone, and
     * adopts tunnels that carry WARP markers but have no row (an imported file, or a restored
     * backup). An import whose slot and variant are free joins that slot; one whose slot is taken
     * waits in [importConflicts] for **Replace existing** or **Import as separate** — never a
     * silent overwrite.
     */
    suspend fun sync(tunnels: List<TunnelConfig>) = mutex.withLock {
        val metas = metaDao.getAll().toMutableList()
        val ids = tunnels.map { it.id }.toSet()
        val orphans = metas.filter { it.tunnelId !in ids }
        if (orphans.isNotEmpty()) {
            metaDao.deleteAll(orphans.map { it.tunnelId })
            metas.removeAll(orphans)
        }
        val byId = tunnels.associateBy { it.id }
        val conflicts = mutableListOf<ImportConflict>()
        val known = metas.map { it.tunnelId }.toSet()
        for (tunnel in tunnels) {
            if (tunnel.id in known) continue
            val markers = WarpMarkers.parse(tunnel.quickConfig) ?: continue
            val existing = metas.firstOrNull {
                it.warpSlot == markers.slot && it.warpVariant == markers.variant.tag
            }
            if (existing != null) {
                conflicts +=
                    ImportConflict(
                        importedId = tunnel.id,
                        importedName = tunnel.name,
                        existingId = existing.tunnelId,
                        existingName = byId[existing.tunnelId]?.name.orEmpty(),
                        slot = markers.slot,
                    )
                continue
            }
            val meta = adoptedMeta(tunnel.id, markers, markers.slot)
            metaDao.upsert(meta)
            metas += meta
        }
        _importConflicts.value = conflicts
    }

    /** Puts the imported config into the existing tunnel (same ID) and removes the import. */
    suspend fun replaceExisting(conflict: ImportConflict) {
        val running: Boolean
        mutex.withLock {
            val imported = tunnelRepository.getById(conflict.importedId) ?: return
            val existing = tunnelRepository.getById(conflict.existingId) ?: return
            val markers = WarpMarkers.parse(imported.quickConfig) ?: return
            val old = metaDao.get(existing.id)
            tunnelRepository.save(existing.copy(quickConfig = imported.quickConfig))
            tunnelRepository.delete(imported)
            metaDao.upsert(adoptedMeta(existing.id, markers, markers.slot))
            // The file carries no registration token; the old one is no longer used here.
            old?.let { deleteRegistrationIfUnused(it) }
            _importConflicts.value =
                _importConflicts.value.filterNot { it.importedId == imported.id }
            running = existing.id in tunnelProvider.backendStatus.value.activeTunnels
        }
        if (running) tunnelCoordinator.value.restartActiveTunnels()
    }

    /** Keeps the import as its own tunnel in the next free slot (e.g. warp-2). */
    suspend fun importSeparately(conflict: ImportConflict) = mutex.withLock {
        val imported = tunnelRepository.getById(conflict.importedId) ?: return@withLock
        val markers = WarpMarkers.parse(imported.quickConfig) ?: return@withLock
        val metas = metaDao.getAll()
        val slot =
            nextFreeSlot(markers.slot) { candidate ->
                metas.any { it.warpSlot == candidate && it.warpVariant == markers.variant.tag }
            }
        // Meta first, so the save below doesn't bring the conflict back.
        metaDao.upsert(adoptedMeta(imported.id, markers, slot))
        tunnelRepository.save(
            imported.copy(
                quickConfig = WarpMarkers.write(imported.quickConfig, markers.copy(slot = slot))
            )
        )
        _importConflicts.value = _importConflicts.value.filterNot { it.importedId == imported.id }
    }

    private fun adoptedMeta(tunnelId: Int, markers: WarpMarkers, slot: String) =
        TunnelMeta(
            tunnelId = tunnelId,
            source = TunnelMeta.SOURCE_WARP,
            warpSlot = slot,
            warpVariant = markers.variant.tag,
            expiresAt = markers.expiresAt,
        )

    companion object {
        fun nextFreeSlot(slot: String, taken: (String) -> Boolean): String {
            val base = slot.replace(Regex("-\\d+$"), "")
            return generateSequence(2) { it + 1 }.map { "$base-$it" }.first { !taken(it) }
        }

        /** Same scheme as upstream's import: "Name", "Name (1)", "Name (2)", … */
        fun uniqueName(name: String, existing: Set<String>): String =
            if (name !in existing) name
            else generateSequence(1) { it + 1 }.map { "$name ($it)" }.first { it !in existing }
    }
}
