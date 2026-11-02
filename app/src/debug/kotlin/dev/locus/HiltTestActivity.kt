package dev.locus

import androidx.activity.ComponentActivity
import dagger.hilt.android.AndroidEntryPoint

/**
 * Debug builds only: an empty activity with Hilt's view-model factory, which instrumentation tests
 * fill with one screen at a time (the pattern in developer.android.com/training/dependency-injection/hilt-testing).
 * It has to be in the app, not the test APK, to run in the app's process and Hilt graph.
 */
@AndroidEntryPoint
class HiltTestActivity : ComponentActivity()
