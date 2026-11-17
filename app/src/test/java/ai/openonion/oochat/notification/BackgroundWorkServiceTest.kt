package ai.openonion.oochat.notification

import ai.openonion.oochat.ConnectOnionApplication
import ai.openonion.oochat.data.local.ActiveWork
import ai.openonion.oochat.domain.model.NotificationTarget
import android.app.Notification
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/** W16AAPPLE-45: the foreground service that holds the connection while work runs off screen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackgroundWorkServiceTest {

    private val app = ApplicationProvider.getApplicationContext<ConnectOnionApplication>()
    private val keeper = app.backgroundWork as BackgroundWorkKeeper
    private val work = ActiveWork("Harbour", NotificationTarget("0x" + "ab".repeat(32), sessionId = "session-1"))

    private fun startedService() = shadowOf(app).nextStartedService

    private fun runService() = Robolectric.buildService(BackgroundWorkService::class.java).create().startCommand(0, 1).get()

    @Test
    fun `leaving the app with nothing running starts nothing`() {
        keeper.onAppHidden()

        assertNull(startedService())
    }

    @Test
    fun `leaving the app while work runs starts the service, which says who is working`() {
        keeper.update(work)
        keeper.onAppHidden()
        assertEquals(BackgroundWorkService::class.java.name, startedService().component?.className)

        val service = runService()

        val shown = shadowOf(service).lastForegroundNotification
        assertEquals("Harbour is working", shown.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(BACKGROUND_WORK_CHANNEL_ID, shown.channelId)
        val channel = app.getSystemService(NotificationManager::class.java).getNotificationChannel(BACKGROUND_WORK_CHANNEL_ID)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertFalse(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun `the service stops once the work has stayed ended for the grace period`() {
        keeper.update(work)
        keeper.onAppHidden()
        val service = runService()

        keeper.update(null)
        ShadowLooper.idleMainLooper(BACKGROUND_WORK_STOP_GRACE_MS - 1, TimeUnit.MILLISECONDS)
        assertFalse(shadowOf(service).isStoppedBySelf)
        ShadowLooper.idleMainLooper(1, TimeUnit.MILLISECONDS)

        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun `a moment of no work between steps does not stop the service`() {
        keeper.update(work)
        keeper.onAppHidden()
        val service = runService()

        keeper.update(null)
        ShadowLooper.idleMainLooper(2, TimeUnit.SECONDS)
        keeper.update(work)
        ShadowLooper.idleMainLooper(BACKGROUND_WORK_STOP_GRACE_MS, TimeUnit.MILLISECONDS)

        assertFalse(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun `the service stops when the user comes back`() {
        keeper.update(work)
        keeper.onAppHidden()
        val service = runService()

        keeper.onAppShown()

        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun `a service that starts after the work already ended stops at once`() {
        keeper.update(work)
        keeper.onAppHidden()
        keeper.update(null)
        ShadowLooper.idleMainLooper(BACKGROUND_WORK_STOP_GRACE_MS, TimeUnit.MILLISECONDS)

        assertTrue(shadowOf(runService()).isStoppedBySelf)
    }

    @Test
    fun `the service gives up after an hour`() {
        keeper.update(work)
        keeper.onAppHidden()
        val service = runService()

        ShadowLooper.idleMainLooper(BACKGROUND_WORK_MAX_MS - 1, TimeUnit.MILLISECONDS)
        assertFalse(shadowOf(service).isStoppedBySelf)
        ShadowLooper.idleMainLooper(1, TimeUnit.MILLISECONDS)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }
}
