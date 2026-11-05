package app.quartz.core

/** A start refused because the tunnel's expiry has passed (FORK.md §9, expiry). */
class TunnelExpiredException(name: String) : IllegalStateException("$name has expired")
