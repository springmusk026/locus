package dev.locus.core.llm

import dev.locus.core.model.ProviderDrafts
import kotlin.test.Test
import kotlin.test.assertEquals

/** provider_draft on OpenAI's hosted tool search: the namespace line the model sees up front doesn't change (docs/design/provider-drafts.md). */
class ProviderDraftNamespaceTest {
    @Test
    fun `provider_draft joins the connector namespace without changing its description`() {
        assertEquals("connector", OpenAiResponsesModel.namespaceOf(ProviderDrafts.TOOL))
        assertEquals(
            OpenAiResponsesModel.namespaceDescription("connector", listOf("connector_create")),
            OpenAiResponsesModel.namespaceDescription("connector", listOf("connector_create", ProviderDrafts.TOOL)),
        )
    }
}
