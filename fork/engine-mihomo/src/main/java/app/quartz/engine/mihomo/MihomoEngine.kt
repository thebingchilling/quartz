package app.quartz.engine.mihomo

import java.io.File
import java.net.ServerSocket
import java.security.SecureRandom
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class MihomoException(message: String) : Exception(message)

/** Where the running profile's REST API listens (FORK.md §8). */
data class MihomoController(val host: String, val port: Int, val secret: String) {
    val baseUrl: String
        get() = "http://$host:$port"
}

/**
 * mihomo (Clash Meta) in-process: one profile at a time, reading packets from the VpnService TUN
 * it's given and protecting its own sockets through [SocketProtector]. Any profile works as-is: the
 * wrapper forces the TUN, routing and controller settings Android needs.
 *
 * [homeDir] holds mihomo's working files (geodata, provider caches); profiles can live anywhere.
 */
class MihomoEngine(
    private val homeDir: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutex = Mutex()

    /** The running profile's controller, or null when stopped. */
    @Volatile
    var controller: MihomoController? = null
        private set

    val version: String by lazy { load().version() }

    suspend fun start(profile: File, tunFd: Int, protector: SocketProtector): MihomoController =
        withContext(ioDispatcher) {
            mutex.withLock {
                homeDir.mkdirs()
                val next = MihomoController(LOOPBACK, freePort(), newSecret())
                load()
                    .start(
                        homeDir.path,
                        profile.path,
                        tunFd,
                        "$LOOPBACK:${next.port}",
                        next.secret,
                        protector,
                    )
                    ?.let { throw MihomoException(it) }
                controller = next
                next
            }
        }

    suspend fun stop() =
        withContext(ioDispatcher) {
            mutex.withLock {
                if (controller != null) load().stop()
                controller = null
            }
        }

    /** Null when [profile] parses, else mihomo's error. Doesn't touch a running profile. */
    suspend fun validate(profile: File): String? =
        withContext(ioDispatcher) {
            homeDir.mkdirs()
            load().validate(homeDir.path, profile.path)
        }

    private fun load(): MihomoNative {
        loaded
        return MihomoNative
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        val loaded: Unit by lazy { System.loadLibrary("mihomo") }

        fun freePort(): Int =
            ServerSocket(0, 1, java.net.InetAddress.getByName(LOOPBACK)).use { it.localPort }

        fun newSecret(): String =
            ByteArray(16)
                .also { SecureRandom().nextBytes(it) }
                .joinToString("") { "%02x".format(it) }
    }
}
