package app.quartz.core

import kotlinx.coroutines.CoroutineExceptionHandler
import timber.log.Timber

/**
 * Crash guard for background work: a coroutine scope with [handler] logs an exception nothing
 * caught instead of crashing the app (Android kills the process for an uncaught exception in any
 * coroutine). Used by the application scope (QuartzModule) and the engine services' scopes.
 */
object QuartzErrors {
    fun handler(where: String): CoroutineExceptionHandler = CoroutineExceptionHandler { _, error ->
        Timber.e(error, "Uncaught error in $where")
    }
}
