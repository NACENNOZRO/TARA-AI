package ai.tara.personal

import org.junit.Assert.*
import org.junit.Test

class PolicyTest {
    @Test fun pageCannotAuthorizeNewMemory() {
        assertFalse(Policy.mayRemember("Find a cybersecurity internship in Chennai"))
        assertTrue(Policy.mayRemember("Remember that I prefer Chennai internships"))
    }
    @Test fun pageCannotIntroduceItsOwnUrl() {
        val injected = "https://attacker.example/collect"
        assertFalse(Policy.mayReadUrl("Review this pasted internship ad", injected))
        assertTrue(Policy.mayReadUrl("Read https://example.org/job", "https://example.org/job"))
    }
    @Test fun recipientsCannotContainNewHeadersOrMultipleAddresses() {
        assertFalse(Policy.validRecipient("hr@example.org\\r\\nBcc:evil@example.org"))
        assertFalse(Policy.validRecipient("hr@example.org,evil@example.org"))
        assertTrue(Policy.validRecipient("hr@example.org"))
    }
}
