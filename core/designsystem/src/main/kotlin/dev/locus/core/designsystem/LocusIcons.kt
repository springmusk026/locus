package dev.locus.core.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** A small stroke icon set, drawn on a 24-unit grid with 1.8 strokes. Tinted by `Icon`. */
object LocusIcons {
    private fun icon(name: String, block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = block,
        ).build()

    val Send = icon("send") { moveTo(12f, 19f); lineTo(12f, 5f); moveTo(6f, 11f); lineTo(12f, 5f); lineTo(18f, 11f) }
    val Stop = icon("stop") { moveTo(7f, 7f); lineTo(17f, 7f); lineTo(17f, 17f); lineTo(7f, 17f); close() }
    val Add = icon("add") { moveTo(12f, 5f); lineTo(12f, 19f); moveTo(5f, 12f); lineTo(19f, 12f) }
    val Back = icon("back") { moveTo(19f, 12f); lineTo(5f, 12f); moveTo(11f, 6f); lineTo(5f, 12f); lineTo(11f, 18f) }
    val Close = icon("close") { moveTo(6f, 6f); lineTo(18f, 18f); moveTo(18f, 6f); lineTo(6f, 18f) }
    val Check = icon("check") { moveTo(5f, 12.5f); lineTo(10f, 17.5f); lineTo(19f, 7f) }
    val ChevronRight = icon("chevron") { moveTo(9f, 6f); lineTo(15f, 12f); lineTo(9f, 18f) }
    val ChevronLeft = icon("chevron_left") { moveTo(15f, 6f); lineTo(9f, 12f); lineTo(15f, 18f) }
    /** Run again: a circular arrow. */
    val Regenerate = icon("regenerate") {
        moveTo(19.5f, 12f); arcTo(7.5f, 7.5f, 0f, true, true, 17.3f, 6.7f)
        moveTo(19.5f, 3.5f); lineTo(19.5f, 8f); lineTo(15f, 8f)
    }
    /** A fork: one line splitting in two (branches, forked chats). */
    val Branch = icon("branch") {
        moveTo(7f, 4f); lineTo(7f, 20f)
        moveTo(7f, 14f); curveTo(7f, 10f, 17f, 11f, 17f, 7f); lineTo(17f, 4f)
        moveTo(4.5f, 6.5f); lineTo(7f, 4f); lineTo(9.5f, 6.5f)
        moveTo(14.5f, 6.5f); lineTo(17f, 4f); lineTo(19.5f, 6.5f)
    }
    val Settings = icon("settings") {
        moveTo(4f, 7f); lineTo(14f, 7f); moveTo(18f, 7f); lineTo(20f, 7f)
        moveTo(14f, 5f); lineTo(14f, 9f); moveTo(18f, 5f); lineTo(18f, 9f)
        moveTo(4f, 17f); lineTo(6f, 17f); moveTo(10f, 17f); lineTo(20f, 17f)
        moveTo(6f, 15f); lineTo(6f, 19f); moveTo(10f, 15f); lineTo(10f, 19f)
    }
    val Folder = icon("folder") {
        moveTo(3.5f, 7f); lineTo(3.5f, 18f); lineTo(20.5f, 18f); lineTo(20.5f, 8.5f); lineTo(11.5f, 8.5f); lineTo(9.5f, 6f); lineTo(4.5f, 6f); close()
    }
    val File = icon("file") {
        moveTo(6f, 3.5f); lineTo(14f, 3.5f); lineTo(18.5f, 8f); lineTo(18.5f, 20.5f); lineTo(6f, 20.5f); close()
        moveTo(14f, 3.5f); lineTo(14f, 8f); lineTo(18.5f, 8f)
    }
    val Activity = icon("activity") { moveTo(3f, 12f); lineTo(7f, 12f); lineTo(10f, 5f); lineTo(14f, 19f); lineTo(17f, 12f); lineTo(21f, 12f) }
    val Chats = icon("chats") {
        moveTo(4f, 5f); lineTo(20f, 5f); lineTo(20f, 16f); lineTo(10f, 16f); lineTo(6f, 19.5f); lineTo(6f, 16f); lineTo(4f, 16f); close()
    }
    val Trash = icon("trash") {
        moveTo(4.5f, 7f); lineTo(19.5f, 7f); moveTo(9.5f, 7f); lineTo(9.5f, 4.5f); lineTo(14.5f, 4.5f); lineTo(14.5f, 7f)
        moveTo(6.5f, 7f); lineTo(7.5f, 20f); lineTo(16.5f, 20f); lineTo(17.5f, 7f)
    }
    val Restore = icon("restore") { moveTo(4f, 9f); lineTo(15f, 9f); arcTo(5f, 5f, 0f, false, true, 15f, 19f); lineTo(9f, 19f); moveTo(8f, 5f); lineTo(4f, 9f); lineTo(8f, 13f) }
    val Import = icon("import") { moveTo(12f, 4f); lineTo(12f, 15f); moveTo(7.5f, 10.5f); lineTo(12f, 15f); lineTo(16.5f, 10.5f); moveTo(5f, 19.5f); lineTo(19f, 19.5f) }
    val Library = icon("library") {
        moveTo(4f, 5f); lineTo(9f, 5f); lineTo(9f, 19f); lineTo(4f, 19f); close()
        moveTo(9f, 7f); lineTo(14f, 7f); lineTo(14f, 19f); lineTo(9f, 19f)
        moveTo(14.5f, 6.5f); lineTo(18.5f, 5.5f); lineTo(21f, 18f); lineTo(17f, 19f); close()
    }
    val Clock = icon("clock") {
        moveTo(12f, 3.5f); arcTo(8.5f, 8.5f, 0f, true, true, 11.99f, 3.5f); close()
        moveTo(12f, 7.5f); lineTo(12f, 12f); lineTo(15f, 14f)
    }
    val More = icon("more") {
        moveTo(12f, 5f); lineTo(12f, 5.01f); moveTo(12f, 12f); lineTo(12f, 12.01f); moveTo(12f, 19f); lineTo(12f, 19.01f)
    }
    val Mic = icon("mic") {
        moveTo(9f, 6.5f); arcTo(3f, 3f, 0f, false, true, 15f, 6.5f); lineTo(15f, 11.5f); arcTo(3f, 3f, 0f, false, true, 9f, 11.5f); close()
        moveTo(6f, 11f); arcTo(6f, 6f, 0f, false, false, 18f, 11f)
        moveTo(12f, 17f); lineTo(12f, 20.5f)
    }
    val Terminal = icon("terminal") {
        moveTo(3.5f, 5f); lineTo(20.5f, 5f); lineTo(20.5f, 19f); lineTo(3.5f, 19f); close()
        moveTo(7f, 9.5f); lineTo(10f, 12f); lineTo(7f, 14.5f); moveTo(12f, 15f); lineTo(16.5f, 15f)
    }
    val Phone = icon("phone") {
        moveTo(7.5f, 3f); lineTo(16.5f, 3f); lineTo(16.5f, 21f); lineTo(7.5f, 21f); close()
        moveTo(11f, 18f); lineTo(13f, 18f)
    }
    val Plug = icon("plug") {
        moveTo(9f, 3.5f); lineTo(9f, 8f); moveTo(15f, 3.5f); lineTo(15f, 8f)
        moveTo(6.5f, 8f); lineTo(17.5f, 8f); lineTo(17.5f, 11f); arcTo(5.5f, 5.5f, 0f, false, true, 6.5f, 11f); close()
        moveTo(12f, 16.5f); lineTo(12f, 20.5f)
    }
    val Sparkle = icon("sparkle") {
        moveTo(12f, 3f); curveTo(12.6f, 8.5f, 15.5f, 11.4f, 21f, 12f); curveTo(15.5f, 12.6f, 12.6f, 15.5f, 12f, 21f)
        curveTo(11.4f, 15.5f, 8.5f, 12.6f, 3f, 12f); curveTo(8.5f, 11.4f, 11.4f, 8.5f, 12f, 3f); close()
    }
    val Globe = icon("globe") {
        moveTo(12f, 3.5f); arcTo(8.5f, 8.5f, 0f, true, true, 11.99f, 3.5f); close()
        moveTo(3.5f, 12f); lineTo(20.5f, 12f)
        moveTo(12f, 3.5f); curveTo(9.2f, 6.5f, 9.2f, 17.5f, 12f, 20.5f)
        moveTo(12f, 3.5f); curveTo(14.8f, 6.5f, 14.8f, 17.5f, 12f, 20.5f)
    }
    val Tool = icon("tool") {
        moveTo(14.5f, 4.5f); arcTo(4.5f, 4.5f, 0f, false, false, 19.5f, 9.5f); lineTo(17f, 9.5f); lineTo(14.5f, 7f); close()
        moveTo(15.5f, 8.5f); lineTo(5f, 19f)
    }
    val Copy = icon("copy") {
        moveTo(9f, 8.5f); lineTo(19.5f, 8.5f); lineTo(19.5f, 20f); lineTo(9f, 20f); close()
        moveTo(15f, 8.5f); lineTo(15f, 4f); lineTo(4.5f, 4f); lineTo(4.5f, 15.5f); lineTo(9f, 15.5f)
    }
    val Play = icon("play") { moveTo(8f, 5.5f); lineTo(18.5f, 12f); lineTo(8f, 18.5f); close() }
    val Search = icon("search") {
        moveTo(10.5f, 4f); arcTo(6.5f, 6.5f, 0f, true, true, 10.49f, 4f); close()
        moveTo(15.2f, 15.2f); lineTo(20f, 20f)
    }
    val Key = icon("key") {
        moveTo(8f, 10f); arcTo(4f, 4f, 0f, true, true, 7.99f, 10f); close()
        moveTo(11.5f, 13.5f); lineTo(19.5f, 13.5f); moveTo(16.5f, 13.5f); lineTo(16.5f, 17f); moveTo(19.5f, 13.5f); lineTo(19.5f, 16f)
    }
    val Shield = icon("shield") {
        moveTo(12f, 3.5f); lineTo(19f, 6.5f); lineTo(19f, 11.5f); curveTo(19f, 16f, 16f, 19f, 12f, 20.5f); curveTo(8f, 19f, 5f, 16f, 5f, 11.5f); lineTo(5f, 6.5f); close()
    }
    val Server = icon("server") {
        moveTo(4f, 4.5f); lineTo(20f, 4.5f); lineTo(20f, 11f); lineTo(4f, 11f); close()
        moveTo(4f, 13f); lineTo(20f, 13f); lineTo(20f, 19.5f); lineTo(4f, 19.5f); close()
        moveTo(7.5f, 7.75f); lineTo(7.51f, 7.75f); moveTo(7.5f, 16.25f); lineTo(7.51f, 16.25f)
    }
    val Note = icon("note") {
        moveTo(5f, 4f); lineTo(19f, 4f); lineTo(19f, 20f); lineTo(5f, 20f); close()
        moveTo(8.5f, 9f); lineTo(15.5f, 9f); moveTo(8.5f, 12.5f); lineTo(15.5f, 12.5f); moveTo(8.5f, 16f); lineTo(12.5f, 16f)
    }
    private val starPath: PathBuilder.() -> Unit = {
        moveTo(12f, 4f); lineTo(14.29f, 9.44f); lineTo(20.18f, 9.94f); lineTo(15.71f, 13.81f); lineTo(17.05f, 19.56f)
        lineTo(12f, 16.5f); lineTo(6.95f, 19.56f); lineTo(8.29f, 13.81f); lineTo(3.82f, 9.94f); lineTo(9.71f, 9.44f); close()
    }

    /** Not starred. */
    val Star = icon("star", starPath)

    /** Starred: the same star, filled. */
    val StarFilled: ImageVector = ImageVector.Builder("star-filled", 24.dp, 24.dp, 24f, 24f).path(
        fill = SolidColor(Color.Black),
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 1.8f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
        pathBuilder = starPath,
    ).build()

    val Pin = icon("pin") {
        moveTo(9f, 4f); lineTo(15f, 4f); moveTo(10f, 4f); lineTo(10f, 10f); lineTo(7f, 14f); lineTo(17f, 14f); lineTo(14f, 10f); lineTo(14f, 4f)
        moveTo(12f, 14f); lineTo(12f, 20.5f)
    }
}
