package ai.tara.personal

/** Deterministic checks executed outside the AI model's control. */
object Policy {
    fun mayRemember(userMessage: String): Boolean = Regex("(?i)\\b(remember|save (this|that)|keep in memory|nyabagam|ஞாபகம்)\\b").containsMatchIn(userMessage)
    fun mayReadUrl(userMessage: String, url: String): Boolean = url.startsWith("https://") && userMessage.contains(url)
    fun validRecipient(value: String): Boolean = Regex("^[^\\s@,;<>]+@[^\\s@,;<>]+\\.[^\\s@,;<>]+$").matches(value)
}
