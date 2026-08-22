package com.ethran.notable.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ethran.notable.R

/**
 * Asks, once, whether diagnostic logs may be uploaded.
 *
 * Three answers, not two. "Allow" and "Don't send" are the buttons; dismissing the dialog —
 * a back press or a tap outside — leaves the question unanswered, so it is put again next
 * launch. That is what the tri-state consent is for: an install that has never been asked is
 * not an install that said no.
 *
 * The copy names what is collected and where it goes rather than describing it as "help us
 * improve", because the honest answer is the whole point of asking.
 */
@Composable
fun TelemetryConsentDialog(
    onAllow: () -> Unit,
    onDeny: () -> Unit,
    onAskLater: () -> Unit,
) {
    ShowConfirmationDialog(
        title = stringResource(R.string.telemetry_consent_title),
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(text = stringResource(R.string.telemetry_consent_body), fontSize = 16.sp)
                Text(text = stringResource(R.string.telemetry_consent_detail), fontSize = 14.sp)
            }
        },
        onConfirm = onAllow,
        onCancel = onDeny,
        onDismiss = onAskLater,
        confirmButtonText = stringResource(R.string.telemetry_consent_allow),
        cancelButtonText = stringResource(R.string.telemetry_consent_deny),
    )
}
