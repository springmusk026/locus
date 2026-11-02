package dev.locus.core.llm

import kotlinx.serialization.json.JsonObject

/**
 * The JSON body this model sends for [request], built by the adapter's own `buildBody`, the same
 * function its `stream` serialises. Read-only: no network, no credentials (those travel in headers
 * and are never part of the body). Null for models that don't speak HTTP JSON (on-device).
 *
 * Used by the "What was sent" viewer, which rebuilds a captured request on demand instead of
 * keeping serialised bodies around.
 */
fun ChatModel.wireBody(request: ChatRequest): JsonObject? = when (this) {
    is AnthropicChatModel -> buildBody(request)
    is OpenAiChatModel -> buildBody(request)
    is OpenAiResponsesModel -> buildBody(request)
    else -> null
}
