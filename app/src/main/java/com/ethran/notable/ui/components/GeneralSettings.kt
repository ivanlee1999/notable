package com.ethran.notable.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.ethran.notable.R
import com.ethran.notable.data.datastore.AppSettings
import com.ethran.notable.data.datastore.MAX_IMAGE_SIZE_STEPS
import com.ethran.notable.data.model.PageSizePreset


@Composable
fun GeneralSettings(
    settings: AppSettings, onSettingsChange: (AppSettings) -> Unit
) {
    Column {
        SelectorRow(
            label = stringResource(R.string.default_page_background_template), options = listOf(
                "blank" to stringResource(R.string.blank_page),
                "dotted" to stringResource(R.string.dot_grid),
                "lined" to stringResource(R.string.lines),
                "squared" to stringResource(R.string.small_squares_grid),
                "hexed" to stringResource(R.string.hexagon_grid),
            ), value = settings.defaultNativeTemplate, onValueChange = {
                onSettingsChange(settings.copy(defaultNativeTemplate = it))
            })
        // Applies to notebooks created from now on. Existing pages keep the sheet
        // they were written against — changing a page's size would move its ink relative to the
        // paper — so this is not a way to reflow what already exists.
        SelectorRow(
            label = stringResource(R.string.default_page_size),
            options = PageSizePreset.entries.map { it.key to it.label },
            value = settings.defaultPageSizeKey,
            onValueChange = { onSettingsChange(settings.copy(defaultPageSizeKey = it)) })
        // Left/Right dock the rail vertically — the tablet arrangement, where it stays within
        // thumb reach of the bezel and the page keeps its full height.
        SelectorRow(
            label = stringResource(R.string.toolbar_position), options = listOf(
                AppSettings.Position.Top to stringResource(R.string.toolbar_position_top),
                AppSettings.Position.Bottom to stringResource(R.string.toolbar_position_bottom),
                AppSettings.Position.Left to stringResource(R.string.toolbar_position_left),
                AppSettings.Position.Right to stringResource(R.string.toolbar_position_right),
            ), value = settings.toolbarPosition, onValueChange = { newPosition ->
                onSettingsChange(settings.copy(toolbarPosition = newPosition))
            })

        SettingToggleRow(
            label = stringResource(R.string.enable_scribble_to_erase),
            value = settings.scribbleToEraseEnabled,
            onToggle = { isChecked ->
                onSettingsChange(settings.copy(scribbleToEraseEnabled = isChecked))
            })

        // This is the single page-navigation choice. Scrolling fits the page width and flows
        // through physical page seams; Pagination fits a whole sheet and turns it at once.
        // Paged supersedes smooth scrolling, which is why that toggle drops away below.
        SelectorRow(
            label = stringResource(R.string.vertical_navigation), options = listOf(
                AppSettings.VerticalNavigation.Continuous to
                        stringResource(R.string.vertical_navigation_continuous),
                AppSettings.VerticalNavigation.Paged to
                        stringResource(R.string.vertical_navigation_paged),
            ), value = settings.verticalNavigation, onValueChange = { mode ->
                onSettingsChange(settings.copy(verticalNavigation = mode))
            })

        if (!settings.verticalNavigation.isPaged) {
            SettingToggleRow(
                label = stringResource(R.string.enable_smooth_scrolling),
                value = settings.smoothScroll,
                onToggle = { isChecked ->
                    onSettingsChange(settings.copy(smoothScroll = isChecked))
                })
        }

        SettingToggleRow(
            label = stringResource(R.string.continuous_zoom),
            value = settings.continuousZoom,
            onToggle = { isChecked ->
                onSettingsChange(settings.copy(continuousZoom = isChecked))
            })
        SettingToggleRow(
            label = stringResource(R.string.continuous_stroke_slider),
            value = settings.continuousStrokeSlider,
            onToggle = { isChecked ->
                onSettingsChange(settings.copy(continuousStrokeSlider = isChecked))
            })
        SettingToggleRow(
            label = stringResource(R.string.rename_on_create),
            value = settings.renameOnCreate,
            onToggle = { isChecked ->
                onSettingsChange(settings.copy(renameOnCreate = isChecked))
            })

        SettingToggleRow(
            label = stringResource(R.string.paginate_pdf),
            value = settings.paginatePdf,
            onToggle = { isChecked ->
                onSettingsChange(settings.copy(paginatePdf = isChecked))
            })

        // Pins the writing surface against knocks. Also reachable from the editor's own menu,
        // which is where it is actually wanted — this row is for making it the default.
        SettingToggleRow(
            label = stringResource(R.string.canvas_lock_title),
            value = settings.canvasLocked,
            onToggle = { isChecked ->
                onSettingsChange(settings.copy(canvasLocked = isChecked))
            })

        // Tri-state on purpose: the panel usually knows better than the user does, but a Kaleido
        // owner who prefers greys and a mono owner who wants the hues back both exist.
        SelectorRow(
            label = stringResource(R.string.greyscale_inks_title), options = listOf(
                null to stringResource(R.string.greyscale_inks_auto),
                true to stringResource(R.string.greyscale_inks_greys),
                false to stringResource(R.string.greyscale_inks_colours),
            ), value = settings.greyscaleInks, onValueChange = { choice ->
                onSettingsChange(settings.copy(greyscaleInks = choice))
            })

        SelectorRow(
            label = stringResource(R.string.max_image_size_title),
            options = MAX_IMAGE_SIZE_STEPS.map { it to "$it px" },
            value = settings.maxImageSize,
            onValueChange = { size -> onSettingsChange(settings.copy(maxImageSize = size)) })

        // Denied is offered as a plain choice rather than hidden behind "advanced": the answer
        // costs the user nothing either way, and an app that buries it is telling on itself.
        SelectorRow(
            label = stringResource(R.string.telemetry_settings_title), options = listOf(
                AppSettings.TelemetryConsent.Granted to
                        stringResource(R.string.telemetry_consent_allow),
                AppSettings.TelemetryConsent.Denied to
                        stringResource(R.string.telemetry_consent_deny),
            ),
            // An install that has not been asked yet shows as "Don't send", which is what is
            // actually happening — nothing is uploaded until the answer is Granted.
            value = if (settings.telemetryConsent == AppSettings.TelemetryConsent.Granted)
                AppSettings.TelemetryConsent.Granted else AppSettings.TelemetryConsent.Denied,
            onValueChange = { choice ->
                onSettingsChange(settings.copy(telemetryConsent = choice))
            })
    }
}
