package dev.locus.core.designsystem

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview

/**
 * A browser's whole top chrome in one row, as mobile browsers do it: a close button that leaves the
 * browser, the address [field] (an [AddressField] with its reload inside), then [actions] (a tab count,
 * the overflow menu). Back, forward and the rest live in the overflow, and the page title isn't shown,
 * so the page keeps the height a second bar took (docs/design/browser.md, "Chrome").
 *
 * While [editing], the field takes the whole row, so a long address and a large font still fit. The
 * bottom edge is a hairline, or the page-load [progress] (0..1) while loading, drawn inside the row's
 * padding so the page never moves when loading starts or stops. Takes the status-bar inset itself:
 * pass it as [LocusScreen]'s `topBar`.
 */
@Composable
fun BrowserToolbar(
    onClose: () -> Unit,
    closeLabel: String,
    editing: Boolean,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    actions: @Composable RowScope.() -> Unit = {},
    field: @Composable () -> Unit,
) {
    Surface(modifier, color = MaterialTheme.colorScheme.background) {
        Box(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = if (editing) Spacing.sm else Spacing.xxs, vertical = Spacing.xxs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!editing) {
                    IconButton(onClick = onClose) { Icon(LocusIcons.Close, contentDescription = closeLabel) }
                }
                Box(Modifier.weight(1f)) { field() }
                if (!editing) actions()
            }
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth()) {
                if (progress != null) {
                    Meter(progress, tone = Tone.Live, size = MeterSize.Hairline, contentDescription = "Page loading")
                } else {
                    Box(Modifier.fillMaxWidth().height(Stroke.hairline).background(Locus.colors.hairline))
                }
            }
        }
    }
}

@Preview(name = "Light", widthDp = 360)
@Preview(name = "Dark", widthDp = 360, uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Composable
internal fun BrowserToolbarPreview() {
    LocusTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                val more: @Composable RowScope.() -> Unit = { IconButton(onClick = {}) { Icon(LocusIcons.More, contentDescription = "More") } }
                val reload: @Composable RowScope.() -> Unit = { IconButton(onClick = {}) { Icon(LocusIcons.Regenerate, contentDescription = "Reload") } }
                BrowserToolbar({}, "Close browser", editing = false, actions = more) {
                    AddressField("booking.com", "", {}, editing = false, onEditingChange = {}, onGo = {}, security = SiteSecurity.Secure, trailing = reload)
                }
                BrowserToolbar({}, "Close browser", editing = false, progress = 0.45f, actions = more) {
                    AddressField("neverssl.com", "", {}, editing = false, onEditingChange = {}, onGo = {}, security = SiteSecurity.NotSecure, trailing = reload)
                }
                BrowserToolbar({}, "Close browser", editing = false, actions = more) {
                    AddressField("secure.checkout.payments.account.booking.com", "", {}, editing = false, onEditingChange = {}, onGo = {}, security = SiteSecurity.Secure, trailing = reload)
                }
                BrowserToolbar({}, "Close browser", editing = true) {
                    AddressField("booking.com", "github.com/springmusk026/locus", {}, editing = true, onEditingChange = {}, onGo = {})
                }
            }
        }
    }
}
