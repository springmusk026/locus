package dev.locus.feature.onboarding

import dev.locus.core.agent.integrations.Integrations
import org.junit.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class IntegrationKeyTest {
    @Test
    fun `keys that can't work are caught before they show Connected`() {
        assertNull(keyProblem(Integrations.ntfy, "https://ntfy.sh/a-long-secret-name"))
        assertNotNull(keyProblem(Integrations.ntfy, "a-long-secret-name"))
        assertNull(keyProblem(Integrations.email, "smtps://me@example.com:pw@smtp.example.com:465 imaps://imap.example.com:993 Agent"))
        assertNotNull(keyProblem(Integrations.email, "my password"))
        // A plain key isn't checked, and an empty field isn't a problem yet.
        assertNull(keyProblem(Integrations.tavily, "anything"))
        assertNull(keyProblem(Integrations.ntfy, ""))
    }
}
