package app.quartz.engine.mihomo

/** Kotlin side of `fork/engine-mihomo/go/jni.c`. Load with [MihomoEngine], not directly. */
internal object MihomoNative {
    /** Returns null on success, else mihomo's error message. */
    @JvmStatic
    external fun start(
        homeDir: String,
        configPath: String,
        tunFd: Int,
        controller: String,
        secret: String,
        protector: SocketProtector,
    ): String?

    @JvmStatic external fun stop()

    /** Returns null when the profile parses, else mihomo's error message. */
    @JvmStatic external fun validate(homeDir: String, configPath: String): String?

    @JvmStatic external fun version(): String
}

/** Called from mihomo's threads for every socket it opens: `VpnService.protect(fd)`. */
fun interface SocketProtector {
    fun protect(fd: Int): Boolean
}
