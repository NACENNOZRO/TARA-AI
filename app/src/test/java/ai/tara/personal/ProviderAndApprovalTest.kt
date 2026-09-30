package ai.tara.personal
import org.junit.Assert.*
import org.junit.Test

class ProviderAndApprovalTest {
    @Test fun localOllamaCannotSendToAnotherDevice() {
        assertTrue(Providers.validBase("ollama", "http://127.0.0.1:11434/v1"))
        assertTrue(Providers.validBase("ollama", "http://localhost:11434/v1"))
        assertFalse(Providers.validBase("ollama", "http://192.168.1.10:11434/v1"))
        assertFalse(Providers.validBase("ollama", "https://attacker.example/v1"))
    }
    @Test fun cloudPresetCannotRedirectCredentials() {
        assertTrue(Providers.validBase("groq", "https://api.groq.com/openai/v1"))
        assertFalse(Providers.validBase("groq", "https://api.groq.com.attacker.example/v1"))
        assertFalse(Providers.validBase("groq", "http://api.groq.com/openai/v1"))
        assertFalse(Providers.validBase("custom", "https://secret@api.example.com/v1"))
    }
    @Test fun customRequiresHttpsAndNoQueryCredentials() {
        assertTrue(Providers.validBase("custom", "https://api.example.com/v1"))
        assertFalse(Providers.validBase("custom", "http://api.example.com/v1"))
        assertFalse(Providers.validBase("custom", "https://api.example.com/v1?key=secret"))
    }
    @Test fun completedAndRunningActionsCannotBeApprovedAgain() {
        assertFalse(ActionRules.canApprove("SUCCESS", 100, 200))
        assertFalse(ActionRules.canApprove("RUNNING", 100, 200))
        assertFalse(ActionRules.canApprove("REJECTED", 100, 200))
        assertTrue(ActionRules.canApprove("PENDING", 100, 200))
    }
    @Test fun expiredAndFutureApprovalsAreRejected() {
        assertFalse(ActionRules.canApprove("PENDING", 0, 86_400_000))
        assertFalse(ActionRules.canApprove("PENDING", 300, 200))
    }
}
