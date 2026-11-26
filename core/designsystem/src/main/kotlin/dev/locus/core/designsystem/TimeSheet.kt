package dev.locus.core.designsystem

import android.text.format.DateFormat
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource

/**
 * Picks a time of day on the one sheet ([LocusSheet]): Material's clock dial, in the phone's 12- or
 * 24-hour format. [minuteOfDay] is where it starts (minutes after midnight) and [onPick] gets the
 * chosen one when Done is pressed; swiping the sheet away keeps the old time. The dial's state is
 * saved, so turning the phone keeps the time being picked.
 *
 * [clearLabel] with [onClear] adds a way out that isn't a time ("Right after saving").
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeSheet(
    title: String,
    minuteOfDay: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
    subtitle: String? = null,
    clearLabel: String? = null,
    onClear: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val minute = minuteOfDay.mod(MINUTES_PER_DAY)
    val state = rememberTimePickerState(initialHour = minute / 60, initialMinute = minute % 60, is24Hour = DateFormat.is24HourFormat(context))
    LocusSheet(
        title = title,
        onDismiss = onDismiss,
        subtitle = subtitle,
        action = stringResource(R.string.common_done),
        onAction = { onPick(state.hour * 60 + state.minute) },
    ) {
        TimePicker(state, Modifier.align(Alignment.CenterHorizontally))
        if (clearLabel != null && onClear != null) {
            TextButton(onClick = onClear, modifier = Modifier.fillMaxWidth()) { Text(clearLabel) }
        }
    }
}

private const val MINUTES_PER_DAY = 24 * 60
