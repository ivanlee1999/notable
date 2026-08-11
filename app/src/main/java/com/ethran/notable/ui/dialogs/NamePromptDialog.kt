package com.ethran.notable.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.ethran.notable.R

/**
 * The one place anything in the app is given a name.
 *
 * Creating and renaming ask the same question, so they get the same dialog: the current or
 * suggested name arrives pre-filled and fully selected, which makes "accept it" and "replace it"
 * both a single gesture. That matters more here than on a phone — every extra tap on e-ink is a
 * screen refresh the user waits through.
 *
 * The value is committed on Done or Save and nowhere else. An earlier version of the rename
 * fields wrote through on focus loss, which made saving depend on a focus event arriving during
 * dialog teardown; a name the user typed is not something to leave to that.
 */
@Composable
fun NamePromptDialog(
    title: String,
    initialValue: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    confirmButtonText: String = stringResource(R.string.name_prompt_save),
    cancelButtonText: String = stringResource(R.string.name_prompt_cancel)
) {
    // Selection spans the whole initial value so the first keystroke replaces it.
    var value by remember {
        mutableStateOf(
            TextFieldValue(text = initialValue, selection = TextRange(0, initialValue.length))
        )
    }
    val focusRequester = remember { FocusRequester() }

    // Nothing else in the app auto-focuses, so the keyboard only ever appears where a name is
    // actually being asked for.
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    fun commit() {
        val name = value.text.trim()
        if (name.isEmpty()) onDismiss() else onConfirm(name)
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .background(Color.White)
                .border(1.dp, Color.Black, RectangleShape)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = title, fontWeight = FontWeight.Bold, fontSize = 20.sp)

            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Color(230, 230, 230, 255), RectangleShape)
                    .border(1.dp, Color.Black, RectangleShape)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = { value = it },
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Light,
                        fontSize = 18.sp,
                        color = Color.Black
                    ),
                    cursorBrush = SolidColor(Color.Black),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = { commit() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                )
            }

            Row(
                horizontalArrangement = Arrangement.SpaceAround,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                ActionButton(text = cancelButtonText, onClick = onDismiss)
                ActionButton(text = confirmButtonText, onClick = { commit() })
            }
        }
    }
}
