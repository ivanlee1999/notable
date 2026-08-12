package com.ethran.notable.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ethran.notable.data.SaveState

/**
 * "Not saved", when the page is not.
 *
 * Draws nothing at all in the ordinary case, which is most of the time — a badge that is always on
 * screen is a badge nobody reads, and on e-ink it is also a redraw for every stroke. It appears
 * only once a write has actually failed and is being retried, because that is the one state where
 * what the user can see is not what is on disk: the strokes are on the canvas, they look exactly
 * like saved ones, and a restart would lose them.
 *
 * Deliberately not a dialog and deliberately not blocking. The retry may well succeed on its own —
 * see [com.ethran.notable.data.PageDataManager] — and taking the page away from someone
 * mid-sentence to tell them about a disk error would cost more writing than the error does.
 */
@Composable
fun SaveStateBadge(
    saveState: kotlinx.coroutines.flow.StateFlow<SaveState>,
    modifier: Modifier = Modifier,
) {
    val state by saveState.collectAsStateWithLifecycle()
    val failed = state as? SaveState.Failed ?: return

    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Text(
            text = when {
                !failed.retrying -> "Not saved"
                failed.failing == 1 -> "Not saved — retrying"
                else -> "Not saved — retrying ${failed.failing} changes"
            },
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier
                .padding(top = 6.dp)
                .background(Color.Black, RectangleShape)
                .border(1.dp, Color.Black, RectangleShape)
                .padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}
