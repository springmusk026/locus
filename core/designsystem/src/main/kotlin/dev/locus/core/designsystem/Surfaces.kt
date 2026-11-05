package dev.locus.core.designsystem

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview

/**
 * The surface a code fence, a diagram or an embedded preview sits on, inside a message or a
 * document: a soft fill ([Emphasis.subtleFill] of `onSurface`), a hairline edge and the control
 * radius. It is quieter than a [LocusCard], because it is content, not a thing to act on. The
 * callers (chat code blocks, Mermaid, artifact previews, Workspace's Markdown fences) used to
 * each write the same three lines.
 */
@Composable
fun CodeSurface(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = Emphasis.subtleFill),
        border = BorderStroke(Stroke.hairline, Locus.colors.hairline),
        modifier = modifier,
    ) {
        content()
    }
}

@Preview(name = "Light", widthDp = 360)
@Preview(name = "Dark", widthDp = 360, uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Composable
internal fun CodeSurfacePreview() {
    LocusTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            CodeSurface(Modifier.padding(Spacing.lg).fillMaxWidth()) {
                Column(Modifier.padding(Spacing.md)) {
                    SupportingText("kotlin", style = LocusType.mono)
                    Text("fun main() = println(\"hi\")", style = LocusType.code, modifier = Modifier.padding(top = Spacing.xs))
                }
            }
        }
    }
}
