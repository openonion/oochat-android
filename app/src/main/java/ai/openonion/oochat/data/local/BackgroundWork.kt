package ai.openonion.oochat.data.local

import ai.openonion.oochat.domain.model.NotificationTarget

/** A turn or Work Room run in progress, as the "is working" notification shows it. */
data class ActiveWork(val agentName: String?, val target: NotificationTarget?)

/**
 * Keeps the connection alive while work runs and the app is off screen
 * (W16AAPPLE-45), so its finished and approval notifications still arrive.
 * The real implementation is [ai.openonion.oochat.notification.BackgroundWorkKeeper].
 */
interface BackgroundWork {
    /** What is running now, or null once nothing is. */
    fun update(work: ActiveWork?)

    /** The app is leaving the screen. */
    fun onAppHidden() {}

    /** The app is back on screen. */
    fun onAppShown() {}

    object None : BackgroundWork {
        override fun update(work: ActiveWork?) {}
    }
}
