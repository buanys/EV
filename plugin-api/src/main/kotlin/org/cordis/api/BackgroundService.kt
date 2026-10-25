package org.cordis.api

import org.cordis.ServiceKey

/**
 * Host-mediated background execution for one plugin.
 *
 * A plugin calls [acquire] when it has work that should outlive its visible page, and [release]
 * when that work is done. Requests are reference counted, per plugin and across the whole process:
 * the host keeps one notification-visible foreground service alive while at least one plugin holds
 * a reference, and stops it when the last reference is released.
 *
 * The host refuses [acquire] with [SecurityException] unless the plugin declared `BACKGROUND` in
 * its metadata and the user granted it. Per ADR-0003 this is an API-level gate plus a visible
 * declaration of intent, not a sandbox: the plugin still shares the host process, and the Android
 * platform decides whether a foreground service may start at all (for example, starting one while
 * the app is in the background can be refused).
 *
 * The API deliberately carries no agent semantics and no scheduling: it only buys process liveness,
 * [reason] is for the user-visible notification and diagnostics.
 */
interface BackgroundService {
    /**
     * Takes one reference. Every call must be balanced by one [release].
     *
     * @throws SecurityException when the plugin does not hold `BACKGROUND`.
     * @throws IllegalStateException when the platform refuses to start the foreground service
     *   (for example a background start without an exemption); no reference is taken in that case.
     */
    fun acquire(reason: String)

    /**
     * Drops one reference. Releasing without a matching [acquire] is ignored, so a plugin can clean
     * up defensively without tracking exact counts.
     */
    fun release()

    companion object {
        val Key = ServiceKey<BackgroundService>("host/background")
    }
}
