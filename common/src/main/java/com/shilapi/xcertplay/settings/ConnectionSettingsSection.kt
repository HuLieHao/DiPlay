package com.shilapi.xcertplay.settings

import android.content.Context
import android.os.Build
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode

object ConnectionSettingsSection {
    const val BOARD_MFI_PATH = "/dev/i2c-2"

    fun boardMfiAvailable(context: Context): Boolean =
        context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0 &&
            Build.MODEL.replace('_', '-').equals("FF-5000", ignoreCase = true)

    fun mfiOptions(context: Context, selected: MfiTarget): List<Pair<MfiTarget, String>> = buildList {
        add(MfiTarget.LOCAL to context.getString(R.string.local_offline))
        add(MfiTarget.USB_CH341 to context.getString(R.string.usb_ch341))
        if (boardMfiAvailable(context) || selected == MfiTarget.I2C) {
            add(MfiTarget.I2C to context.getString(R.string.settings_board_mfi))
        }
    }


    fun createMfiTargetChoice(
        context: Context,
        selected: MfiTarget,
        theme: SettingsTheme = SettingsTheme.OVERLAY,
        onSelected: (MfiTarget) -> Unit,
    ): SettingsWidgets.ChoiceRowResult<MfiTarget> = SettingsWidgets.createChoiceRow(
        context = context,
        label = context.getString(R.string.mfi_certificate_signing_target),
        options = mfiOptions(context, selected),
        selected = selected,
        theme = theme,
        onSelected = onSelected,
    )

    fun createWirelessCarPlayRow(
        context: Context,
        checked: Boolean,
        theme: SettingsTheme = SettingsTheme.OVERLAY,
        onChanged: (Boolean) -> Unit,
    ): SettingsWidgets.SwitchRowResult = SettingsWidgets.createSwitchRow(
        context = context,
        label = context.getString(R.string.wireless_carplay_2),
        description = context.getString(R.string.wireless_carplay_transport),
        checked = checked,
        theme = theme,
        contentDescription = context.getString(R.string.wireless_carplay_transport),
        labelSizeSp = if (theme.isOverlay) 20f else 18f,
        onChanged = onChanged,
    )

    fun createHotspotModeChoice(
        context: Context,
        selected: WirelessHotspotMode,
        theme: SettingsTheme = SettingsTheme.OVERLAY,
        onSelected: (WirelessHotspotMode) -> Unit,
    ): SettingsWidgets.ChoiceRowResult<WirelessHotspotMode> {
        // The car hotspot keeps the radio on one channel; Wi-Fi Direct shares it with the car's network search.
        val modes = buildList {
            add(WirelessHotspotMode.MANUAL to context.getString(R.string.settings_built_in_car_hotspot_recommended))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(WirelessHotspotMode.WIFI_P2P to context.getString(R.string.wi_fi_p2p_5_ghz))
            }
            add(WirelessHotspotMode.EXISTING_WIFI to context.getString(R.string.existing_wifi_title))
        }
        return SettingsWidgets.createChoiceRow(
            context = context,
            label = context.getString(R.string.wi_fi_session),
            options = modes,
            selected = selected,
            theme = theme,
            onSelected = onSelected,
        )
    }
}
