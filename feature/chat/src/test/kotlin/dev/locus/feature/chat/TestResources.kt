package dev.locus.feature.chat

import android.content.Context
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider

/** The module's English resources under Robolectric, for tests of text that is looked up when it is shown. */
internal val testResources: Resources get() = ApplicationProvider.getApplicationContext<Context>().resources
