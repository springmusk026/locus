package dev.locus.core.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay

/**
 * A screen's content while its data loads: nothing at first, then a centred indicator if loading takes
 * longer than [delayMs], so a fast load doesn't flash a spinner.
 */
@Composable
fun LoadingState(modifier: Modifier = Modifier, delayMs: Long = 300) {
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(delayMs)
        show = true
    }
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (show) CircularProgressIndicator()
    }
}
