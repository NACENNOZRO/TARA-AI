package ai.tara.personal

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class Provider(val id: String, val label: String, val base: String, val model: String, val note: String, val signup: String)
object Providers {
    val all = listOf(
        Provider("groq", "Groq", "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile", "Free plan with quotas. Model access depends on your account.", "https://console.groq.com/keys"),
        Provider("gemini", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.5-flash", "Free tier on eligible models; check data use and account limits.", "https://aistudio.google.com/apikey"),
        Provider("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", "openrouter/free", "Free router or models ending :free. Strict quotas apply.", "https://openrouter.ai/settings/keys"),
        Provider("mistral", "Mistral", "https://api.mistral.ai/v1", "mistral-small-latest", "Free experiment tier where eligible; provider terms apply.", "https://console.mistral.ai/"),
        Provider("cerebras", "Cerebras", "https://api.cerebras.ai/v1", "", "Choose an available model after connecting. Check current plan.", "https://cloud.cerebras.ai/"),
        Provider("openai", "OpenAI", "https://api.openai.com/v1", "gpt-4o-mini", "Paid API; a ChatGPT subscription does not supply API credit.", "https://platform.openai.com/api-keys"),
        Provider("ollama", "Ollama on this phone", "http://127.0.0.1:11434/v1", "", "Local models; Ollama must remain running. No API key required.", "https://docs.ollama.com/"),
        Provider("custom", "Custom compatible API", "", "", "HTTPS OpenAI-compatible endpoint. You choose the provider and model.", "")
    )
    fun get(id: String) = all.firstOrNull { it.id == id } ?: all.first()
    fun validBase(id: String, base: String): Boolean {
        val url = base.toHttpUrlOrNull() ?: return false
        if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null) return false
        return if (id == "ollama") url.host in setOf("127.0.0.1", "localhost") && url.scheme in setOf("http", "https")
        else url.scheme == "https" && (id == "custom" || url.host == get(id).base.toHttpUrlOrNull()?.host)
    }
}
