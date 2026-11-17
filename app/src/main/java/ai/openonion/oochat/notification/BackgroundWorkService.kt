package ai.openonion.oochat.notification

import ai.openonion.oochat.ConnectOnionApplication
import ai.openonion.oochat.MainActivity
import ai.openonion.oochat.R
import ai.openonion.oochat.data.local.ActiveWork
import ai.openonion.oochat.data.local.BackgroundWork
import ai.openonion.oochat.util.FileLogger
import ai.openonion.oochat.util.LogTags
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

const val BACKGROUND_WORK_CHANNEL_ID = "background_work"

private const val BACKGROUND_WORK_NOTIFICATION_ID = 2004

/** A stuck turn, such as an approval nobody answers, does not hold the connection open longer than this. */
internal const val BACKGROUND_WORK_MAX_MS = 60 * 60 * 1000L

/**
 * How long work must stay ended before the service stops. A run reads as idle
 * for a moment between its steps, and a service stopped then cannot be started
 * again from the background.
 */
internal const val BACKGROUND_WORK_STOP_GRACE_MS = 10_000L

/**
 * Runs [BackgroundWorkService] while work is in progress and the app is off
 * screen. Main thread only. Started as the app leaves, not when work begins,
 * so nothing extra shows while the user is in the app; Android 12+ allows the
 * start only while the app is still visible, which onPause still is.
 */
class BackgroundWorkKeeper(private val context: Context) : BackgroundWork {
    private val handler = Handler(Looper.getMainLooper())
    private val stopAfterGrace = Runnable {
        work = null
        stop("work ended")
    }
    private var work: ActiveWork? = null
    private var hidden = false
    internal var service: BackgroundWorkService? = null

    /** What the service should show, or null when it should not be running. */
    internal val wanted: ActiveWork? get() = work?.takeIf { hidden }

    override fun update(work: ActiveWork?) {
        handler.removeCallbacks(stopAfterGrace)
        if (work == null) {
            // The last work is kept until the grace ends, so a service starting meanwhile still shows it.
            if (this.work != null) handler.postDelayed(stopAfterGrace, BACKGROUND_WORK_STOP_GRACE_MS)
            return
        }
        this.work = work
        service?.show(work)
    }

    override fun onAppHidden() {
        hidden = true
        if (work == null) return
        FileLogger.i(LogTags.NOTIFICATIONS, "Keeping the connection while the app is in the background")
        try {
            ContextCompat.startForegroundService(context, Intent(context, BackgroundWorkService::class.java))
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException: the app was already off screen.
            FileLogger.w(LogTags.NOTIFICATIONS, "Could not keep the connection in the background: ${e.message}")
        }
    }

    override fun onAppShown() {
        hidden = false
        stop("app back on screen")
    }

    private fun stop(reason: String) {
        // Cleared before it is destroyed, so a late show() cannot repost its notification.
        val running = service ?: return
        service = null
        FileLogger.i(LogTags.NOTIFICATIONS, "Background connection released: $reason")
        running.stopSelf()
    }
}

/**
 * Holds the process in the foreground so the shared connection keeps
 * receiving. It does no work itself: the chat's ViewModel still folds the
 * frames and posts the notifications.
 */
class BackgroundWorkService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val stopAtCap = Runnable { stopSelf() }

    private val keeper: BackgroundWorkKeeper?
        get() = (application as? ConnectOnionApplication)?.backgroundWork as? BackgroundWorkKeeper

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val work = keeper?.wanted
        // Before anything else, even to stop at once: Android kills an app whose
        // startForegroundService is not followed by startForeground.
        ServiceCompat.startForeground(
            this,
            BACKGROUND_WORK_NOTIFICATION_ID,
            notification(work),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        )
        if (work == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        keeper?.service = this
        handler.removeCallbacks(stopAtCap)
        handler.postDelayed(stopAtCap, BACKGROUND_WORK_MAX_MS)
        return START_NOT_STICKY
    }

    fun show(work: ActiveWork) {
        // Gated on POST_NOTIFICATIONS by the caller; without it Android still runs the service.
        @Suppress("MissingPermission")
        NotificationManagerCompat.from(this).notify(BACKGROUND_WORK_NOTIFICATION_ID, notification(work))
    }

    /** Android 15's daily limit for a data sync service. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(stopAtCap)
        keeper?.takeIf { it.service === this }?.service = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(work: ActiveWork?): Notification {
        ensureChannel()
        val tap = work?.target?.let { NotificationIntents.open(this, it) }
            ?: Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return NotificationCompat.Builder(this, BACKGROUND_WORK_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_reply)
            .setContentTitle("${work?.agentName?.takeIf { it.isNotBlank() } ?: "Your agent"} is working")
            .setContentText("You'll be notified when it's done")
            .setOngoing(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(PendingIntent.getActivity(this, BACKGROUND_WORK_NOTIFICATION_ID, tap, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .build()
    }

    private fun ensureChannel() {
        val channel = NotificationChannel(BACKGROUND_WORK_CHANNEL_ID, "Background work", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while an agent or a Work Room is working and the app is in the background"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
