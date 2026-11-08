package com.shilapi.xcertplay

import android.app.AlertDialog
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.MfiTarget
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.util.ReflectionHelpers

/** With the CH341 removed CarPlay cannot connect, so its own menu cannot switch back to Local (#696). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], qualifiers = "en", manifest = Config.NONE)
class MfiTargetSettingsTest {
    private lateinit var controller: ActivityController<DiPlayActivity>
    private lateinit var activity: DiPlayActivity
    private lateinit var page: LinearLayout

    @Before fun setUp() {
        controller = Robolectric.buildActivity(DiPlayActivity::class.java)
        activity = controller.get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        activity.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        CarPlayBackgroundSession.clear()
        controller.setup().visible()
    }

    @After fun tearDown() {
        controller.pause().stop().destroy()
        CarPlayBackgroundSession.clear()
    }

    @Test fun connectionSettingsSwitchFromCh341BackToLocalWithoutConnecting() {
        AirPlayPersistence.saveMfiTarget(activity, MfiTarget.USB_CH341)
        renderConnection()
        assertEquals("${activity.getString(R.string.mfi_certificate_signing_target)} · ${activity.getString(R.string.usb_ch341)}",
            targetButton().text.toString())
        assertTrue(views(page).filterIsInstance<TextView>().any {
            it.text == activity.getString(R.string.settings_mfi_target_description)
        })

        targetButton().performClick()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertEquals(activity.getString(R.string.local_offline), dialog.listView.adapter.getItem(0))
        dialog.listView.performItemClick(null, 0, 0)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(MfiTarget.LOCAL, AirPlayPersistence.loadMfiTarget(activity))
        assertEquals("${activity.getString(R.string.mfi_certificate_signing_target)} · ${activity.getString(R.string.local_offline)}",
            targetButton().text.toString())
    }

    @Test fun expertTargetsSetOutsideTheTwoChoicesAreNotOverwritten() {
        AirPlayPersistence.saveMfiTarget(activity, MfiTarget.REMOTE)
        renderConnection()

        assertTrue(views(page).filterIsInstance<Button>().none {
            it.text.startsWith(activity.getString(R.string.mfi_certificate_signing_target))
        })
        assertEquals(MfiTarget.REMOTE, AirPlayPersistence.loadMfiTarget(activity))
    }

    @Test fun boardMfiCanBeSelectedAndReturnedToSoftware() {
        org.robolectric.shadows.ShadowBuild.setModel("FF_5000")
        activity.applicationInfo.flags = activity.applicationInfo.flags or android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE
        renderConnection()
        targetButton().performClick()
        var dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertEquals(3, dialog.listView.adapter.count)
        dialog.listView.performItemClick(null, 2, 2)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        assertEquals(MfiTarget.LOCAL, AirPlayPersistence.loadMfiTarget(activity))
        targetButton().performClick()
        dialog = ShadowAlertDialog.getLatestAlertDialog()
        dialog.listView.performItemClick(null, 2, 2)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(MfiTarget.I2C, AirPlayPersistence.loadMfiTarget(activity))
        assertEquals("/dev/i2c-2", AirPlayPersistence.loadMfiI2cPath(activity))
        renderConnection()
        targetButton().performClick()
        dialog = ShadowAlertDialog.getLatestAlertDialog()
        dialog.listView.performItemClick(null, 0, 0)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(MfiTarget.LOCAL, AirPlayPersistence.loadMfiTarget(activity))
    }

    @Test fun releaseBuildDoesNotOfferBoardMfiButSavedI2cCanBeReverted() {
        org.robolectric.shadows.ShadowBuild.setModel("FF_5000")
        activity.applicationInfo.flags = activity.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE.inv()
        renderConnection()
        targetButton().performClick()
        assertEquals(2, ShadowAlertDialog.getLatestAlertDialog().listView.adapter.count)
        ShadowAlertDialog.getLatestAlertDialog().dismiss()
        AirPlayPersistence.saveMfiTarget(activity, MfiTarget.I2C)
        renderConnection()
        targetButton().performClick()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        dialog.listView.performItemClick(null, 0, 0)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(MfiTarget.LOCAL, AirPlayPersistence.loadMfiTarget(activity))
    }

    @Test fun boardSelectionKeepsActiveSessionUntilExplicitReconnect() {
        org.robolectric.shadows.ShadowBuild.setModel("FF_5000")
        activity.applicationInfo.flags = activity.applicationInfo.flags or android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE
        val session = org.mockito.Mockito.mock(com.shilapi.xcertplay.orchestration.CarPlayController::class.java)
        var stops = 0
        CarPlayBackgroundSession.store(session, org.mockito.Mockito.mock(com.shilapi.xcertplay.media.AndroidMediaSink::class.java), 800, 480, Any(),
            CarPlaySessionDisplay(800, 480, android.view.Surface.ROTATION_0, false, false, 800, 480)) { stops++ }
        CarPlayBackgroundSession.active = true
        PendingReconnect.clear()
        renderConnection()
        targetButton().performClick()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertEquals(activity.getString(R.string.save), dialog.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
        dialog.listView.performItemClick(null, 2, 2)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(MfiTarget.I2C, AirPlayPersistence.loadMfiTarget(activity))
        assertTrue(PendingReconnect.isPending(session))
        assertEquals(0, stops)
        org.junit.Assert.assertSame(session, CarPlayBackgroundSession.snapshot()?.controller)
        PendingReconnect.clear()
    }

    private fun renderConnection() {
        ReflectionHelpers.setField(activity, "page", "settings")
        ReflectionHelpers.setField(activity, "settingsCategory", SettingsCategory.CONNECTION)
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "render")
        page = ReflectionHelpers.getField<ScrollView>(activity, "rootScroll").getChildAt(0) as LinearLayout
    }

    private fun targetButton() = views(page).filterIsInstance<Button>().single {
        it.text.startsWith(activity.getString(R.string.mfi_certificate_signing_target) + " · ")
    }

    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(views(view.getChildAt(index)))
    }
}
