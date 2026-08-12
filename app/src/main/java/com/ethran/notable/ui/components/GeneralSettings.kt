package com.ethran.notable.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.ethran.notable.R
import com.ethran.notable.data.datastore.AppSettings
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
        // Applies to notebooks and quick pages created from now on. Existing pages keep the sheet
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

        // Paged turns by a whole screen at a time, so a turn is one clean refresh rather
        // than a run of partial ones. It supersedes smooth scrolling, which is why that
        // toggle drops away below when it is selected.
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

        SettingToggleRow(
            label = stringResource(R.string.preview_pdf_pagination),
            value = settings.visualizePdfPagination,
            onToggle = { isChecked ->
                onSettingsChange(settings.copy(visualizePdfPagination = isChecked))
            })
    }
}
