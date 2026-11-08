package com.shilapi.xcertplay

import android.content.Intent
import android.app.ActivityManager
import android.content.ComponentName
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import org.robolectric.shadows.ShadowAppTask

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27, 33], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class ManualDisconnectNavigationTest {
    @Before fun setup() {
        CarPlayBackgroundSession.clear()
        CarPlayBackgroundSession.allowAutomaticConnection()
    }

    @After fun cleanup() {
        CarPlayBackgroundSession.clear()
        CarPlayBackgroundSession.allowAutomaticConnection()
    }

    @Test fun returningHomeTargetsTheExistingManagementTask() {
        val host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        ReflectionHelpers.callInstanceMethod<Unit>(host, "showDiPlayHome",
            ReflectionHelpers.ClassParameter(String::class.java, "home"))
        val intent = shadowOf(host).nextStartedActivity
        assertEquals(DiPlayActivity::class.java.name, intent.component?.className)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
        assertEquals("home", intent.getStringExtra("page"))
        assertNotNull(CarPlayBackgroundSession.automaticConnectionTicket())
    }

    @Test fun aRestoredStoppedHostCannotWaitForPermissionsOrStartAgain() {
        val host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        CarPlayBackgroundSession.stop()
        ReflectionHelpers.callInstanceMethod<Unit>(host, "maybeStartCarPlay")
        assertTrue("Stopped host must close instead of remaining a connection screen", host.isFinishing)
        assertEquals(DiPlayActivity::class.java.name,
            shadowOf(host).nextStartedActivity?.component?.className)
        assertNull(CarPlayBackgroundSession.automaticConnectionTicket())
    }

    @Test fun anExplicitNewAttemptCanUseANewHost() {
        CarPlayBackgroundSession.stop()
        CarPlayBackgroundSession.allowAutomaticConnection()
        val host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        ReflectionHelpers.callInstanceMethod<Unit>(host, "maybeStartCarPlay")
        assertFalse(host.isFinishing)
        assertNull(shadowOf(host).nextStartedActivity)
    }

    @Test fun restoringAStoppedActivityFinishesBeforeRequestingConnectionPermissions() {
        CarPlayBackgroundSession.stop()
        val lifecycle = Robolectric.buildActivity(CarPlayHostActivity::class.java).create()
        assertTrue(lifecycle.get().isFinishing)
        assertEquals(DiPlayActivity::class.java.name,
            shadowOf(lifecycle.get()).nextStartedActivity?.component?.className)
        lifecycle.destroy()
    }

    @Test fun disconnectRemovesOnlyProjectionTasksIncludingEmptyRecentCards() {
        val home = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        fun task(type: Class<*>, id: Int) = ShadowAppTask.newInstance().also {
            shadowOf(it).setTaskInfo(ActivityManager.RecentTaskInfo().apply {
                this.id = id
                baseIntent = Intent().setComponent(ComponentName(home, type))
                numActivities = 0
            })
        }
        val projection = task(CarPlayHostActivity::class.java, 23)
        val management = task(DiPlayActivity::class.java, 24)
        shadowOf(home.getSystemService(ActivityManager::class.java))
            .setAppTasks(listOf(projection, management))
        ReflectionHelpers.callInstanceMethod<Unit>(OwnScreenStart, "removeCarPlayTasks",
            ReflectionHelpers.ClassParameter(android.app.Activity::class.java, home))
        assertTrue(shadowOf(projection).isFinishedAndRemoved)
        assertFalse(shadowOf(management).isFinishedAndRemoved)
    }
}
