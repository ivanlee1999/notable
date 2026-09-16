package com.ethran.notable.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.material.Text
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ethran.notable.editor.EditorControlTower
import com.ethran.notable.editor.PageView
import com.ethran.notable.editor.text.TextBoxLayout
import com.ethran.notable.editor.text.TextBoxMetrics
import com.ethran.notable.ui.noRippleClickable

/**
 * The field a text box is typed into, laid over the canvas at the box's place on the page.
 *
 * Only while a box is open. The rest of the time a box is a *picture* — drawn into the page
 * bitmap with everything else (see [com.ethran.notable.editor.text.TextBoxLayout]) — so the page
 * carries no views at all and scrolling stays a bitmap blit.
 *
 * It shows the box's **markdown source**, not its rendered form. While it is being edited it *is*
 * source: a heading that doubled in size the moment the hash was typed would reflow the line under
 * the caret.
 */
@Composable
fun TextBoxEditor(
    controlTower: EditorControlTower,
    page: PageView,
    metrics: TextBoxMetrics = TextBoxMetrics.STANDARD,
) {
    val state = controlTower.textEditState
    val block = state.editing ?: return
    val x = block.x ?: return
    val y = block.y ?: return

    val zoom by page.zoomLevel.collectAsStateWithLifecycle()
    val scroll = page.scroll
    val density = LocalDensity.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember(block.id) { FocusRequester() }

    // Page units to screen pixels to Compose dp: the same conversion the canvas does, spelled
    // out because Compose lays out in dp and the page is drawn in units.
    fun px(units: Float) = units * zoom
    fun dp(units: Float) = with(density) { px(units).toDp() }

    val width = (block.width ?: metrics.preferredWidth.toInt()).toFloat()
    val height = TextBoxLayout.measuredHeight(state.draft, width, metrics).toFloat()

    Box(Modifier.fillMaxSize()) {
        BasicTextField(
            value = state.draft,
            onValueChange = { state.draft = it },
            modifier = Modifier
                .offset(x = dp(x - scroll.x), y = dp(y - scroll.y))
                .width(dp(width))
                .heightIn(min = dp(height))
                .background(Color.White)
                .border(1.dp, Color(0xFF7D7979))
                .padding(dp(metrics.padding))
                .focusRequester(focusRequester),
            textStyle = TextStyle(
                color = Color.Black,
                fontSize = with(density) { px(metrics.body).toSp() },
            ),
            // A blinking caret is a refresh every half second, which on e-ink is a flicker that
            // never stops. The bar is drawn as part of the text instead — still there, still
            // where the caret is, and still.
            cursorBrush = SolidColor(Color.Black),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { controlTower.commitTextBox() }),
        )

        Row(Modifier.offset(x = dp(x - scroll.x), y = dp(y - scroll.y + height) + 4.dp)) {
            TextBoxBarButton("Delete") { controlTower.deleteOpenTextBox() }
            TextBoxBarButton("Done") { controlTower.commitTextBox() }
        }
    }

    LaunchedEffect(block.id) {
        focusRequester.requestFocus()
        keyboard?.show()
    }
}

/**
 * One button on the bar under an open box.
 *
 * Deliberately plain and deliberately square-edged: this is the same flat, hard-edged chrome the
 * rail is drawn in, and on e-ink a filled rectangle with a border reads at a glance where a subtle
 * one does not.
 */
@Composable
private fun TextBoxBarButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .padding(end = 4.dp)
            .background(Color.White)
            .border(1.dp, Color.Black)
            .noRippleClickable(onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(label, fontSize = 13.sp, color = Color.Black)
    }
}
