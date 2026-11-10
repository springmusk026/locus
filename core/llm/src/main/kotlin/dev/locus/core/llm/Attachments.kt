package dev.locus.core.llm

import dev.locus.core.model.Part
import java.io.File
import java.util.Base64

/** How an attachment is sent: inline image, inline PDF document, or a note pointing at the workspace file. */
internal sealed interface AttachmentPayload {
    data class Image(val mimeType: String, val base64: String) : AttachmentPayload
    data class Pdf(val base64: String) : AttachmentPayload
    data class Note(val text: String) : AttachmentPayload
}

private val IMAGE_TYPES = setOf("image/jpeg", "image/png", "image/gif", "image/webp")
private const val MAX_INLINE_BYTES = 5L * 1024 * 1024

internal fun note(a: Part.Attachment) =
    "[Attached file: ${a.workspacePath} (${a.mimeType}, ${a.sizeBytes} bytes). It is in the workspace; read or process it with your tools.]"

/** "chart.png, 1568×980": how a tool's image (view_image) is named in text the model reads. */
fun toolImageLabel(a: Part.Attachment): String =
    a.name + if (a.width != null && a.height != null) ", ${a.width}×${a.height}" else ""

/**
 * The text a model that gets no pixels from tool results reads for [images] (on-device models), or
 * null when there are none. Older results without images read exactly as before.
 */
fun toolImagesNotShown(images: List<Part.Attachment>): String? =
    if (images.isEmpty()) null
    else "[Not shown: ${images.joinToString("; ") { toolImageLabel(it) }}. This model can't see images in tool results.]"

internal fun payload(a: Part.Attachment, allowPdf: Boolean): AttachmentPayload {
    val file = File(a.path)
    val inline = file.isFile && file.length() <= MAX_INLINE_BYTES
    return when {
        inline && a.mimeType in IMAGE_TYPES -> AttachmentPayload.Image(a.mimeType, Base64.getEncoder().encodeToString(file.readBytes()))
        inline && allowPdf && a.mimeType == "application/pdf" -> AttachmentPayload.Pdf(Base64.getEncoder().encodeToString(file.readBytes()))
        else -> AttachmentPayload.Note(note(a))
    }
}
