package ai.tara.personal
object ActionRules {
    fun canApprove(state: String, created: Long, now: Long): Boolean = state == "PENDING" && now >= created && now - created < 86_400_000L
}
