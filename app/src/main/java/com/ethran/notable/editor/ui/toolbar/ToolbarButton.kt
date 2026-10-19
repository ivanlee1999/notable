package com.ethran.notable.editor.ui.toolbar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import com.ethran.notable.data.datastore.BUTTON_SIZE
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ethran.notable.ui.noRippleClickable

@Composable
fun ToolbarButton(
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
    onSelect: () -> Unit = {},
    iconId: Int? = null,
    vectorIcon: ImageVector? = null,
    imageVector: ImageVector? = null,
    text: String? = null,
    penColor: Color? = null,
    contentDescription: String = ""
) {
    val bgColor = if (isSelected) Color.Black else Color.Transparent
    val tint = if (isSelected) Color.White else Color.Black

    Box(
        Modifier
            .then(modifier)
            .size(BUTTON_SIZE.dp)
            .semantics { selected = isSelected }
            .noRippleClickable { onSelect() }
            .background(
                color = bgColor, shape = RectangleShape
            )
            .padding(7.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            iconId != null -> {
                Icon(painterResource(iconId), contentDescription, tint = tint)
            }

            vectorIcon != null -> {
                Icon(vectorIcon, contentDescription, tint = tint)
            }

            imageVector != null -> {
                Icon(
                    imageVector,
                    contentDescription,
                    tint = if (isSelected) Color.White else Color.Black
                )
            }

            text != null -> {
                Text(
                    text, fontSize = 20.sp, color = if (isSelected) Color.White else Color.Black
                )
            }
        }
        // Keep presets recognizable without painting every button in its ink color.
        if (penColor != null) {
            Box(
                Modifier.align(Alignment.BottomEnd).size(8.dp)
                    .background(penColor, CircleShape)
                    .border(1.dp, tint, CircleShape)
            )
        }
    }
}
