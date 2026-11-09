package app.quartz.warp

import app.quartz.warp.awg.AwgParams

/** A registration plus what's needed to build its config for any options (shared: Android, desktop). */
data class GeneratedWarp(
    val registration: WarpRegistration,
    val awg: AwgParams,
    val expiresAt: Long,
) {
    fun endpoint(options: WarpOptions) =
        WarpConfigBuilder.resolveEndpoint(options.endpoint, registration)

    fun config(options: WarpOptions): String =
        WarpConfigBuilder.build(
            registration,
            options.variant,
            options,
            endpoint(options),
            awg.takeIf { options.variant == WarpVariant.AWG },
            expiresAt,
        )
}
