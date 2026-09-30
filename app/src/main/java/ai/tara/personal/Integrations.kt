package ai.tara.personal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.json.JSONArray
import javax.inject.Inject

object Connections {
    data class Spec(val id: String, val name: String, val target: String, val help: String, val url: String)
    val all = listOf(
        Spec("linkedin", "LinkedIn", "Person URN (urn:li:person:...)", "Publish a text post. Requires a LinkedIn developer app, approved posting product and a user access token with w_member_social. Not a jobs scraping API.", "https://www.linkedin.com/developers/apps"),
        Spec("github", "GitHub", "Repository (owner/repo)", "Read repositories and create issues after review. Use a fine-grained token with Issues write for the selected repository.", "https://github.com/settings/personal-access-tokens"),
        Spec("telegram", "Telegram", "Chat ID", "Send bot messages after review. Create a bot with BotFather and start its chat before connecting.", "https://core.telegram.org/bots/tutorial"),
        Spec("slack", "Slack", "Channel ID", "Send a message after review. Install your Slack app with chat:write; add the bot to the chosen channel.", "https://api.slack.com/apps"),
        Spec("notion", "Notion", "Parent page ID", "Create pages after review. Share the parent page with the integration; token needs insert content.", "https://www.notion.so/profile/integrations"),
        Spec("brave", "Brave Search", "Not used", "Search the public web. Supply a Search API subscription token. Check current account quotas and pricing.", "https://api-dashboard.search.brave.com/")
    )
}
class Integrations @Inject constructor(private val vault: Vault, private val http: OkHttpClient, private val dao: TaraDao) {
    private suspend fun request(service: String, path: String, payload: JSONObject? = null): JSONObject = withContext(Dispatchers.IO) {
        val token = vault.get("connection_${service}_key"); require(token.isNotBlank()) { "Connect $service in Settings." }
        val root = when (service) { "github" -> "https://api.github.com"; "linkedin" -> "https://api.linkedin.com"; "slack" -> "https://slack.com/api"; "notion" -> "https://api.notion.com/v1"; "telegram" -> "https://api.telegram.org/bot$token"; "brave" -> "https://api.search.brave.com"; else -> error("Unsupported integration") }
        val req = Request.Builder().url(root + path).apply {
            if (service == "brave") header("X-Subscription-Token", token)
            else if (service != "telegram") header("Authorization", "Bearer $token")
            if (service == "linkedin") { header("LinkedIn-Version", vault.get("linkedin_version").ifBlank { "202609" }); header("X-Restli-Protocol-Version", "2.0.0") }
            if (service == "notion") header("Notion-Version", "2022-06-28")
            if (service == "github") header("Accept", "application/vnd.github+json")
            if (payload != null) post(payload.toString().toRequestBody("application/json".toMediaType()))
        }.build()
        http.newBuilder().followRedirects(false).retryOnConnectionFailure(false).build().newCall(req).await().use { response ->
            require(response.isSuccessful) { "$service returned HTTP ${response.code}. Check token scopes, expiry and destination." }
            val raw = response.body?.string().orEmpty()
            val obj = if (raw.isBlank()) JSONObject() else if (raw.startsWith("[")) JSONObject().put("items", JSONArray(raw)) else JSONObject(raw)
            require(!obj.has("ok") || obj.optBoolean("ok")) { "$service rejected the request (${obj.optString("error", "check configuration")})" }
            response.header("x-restli-id")?.let { obj.put("provider_id", it) }
            obj
        }
    }
    suspend fun test(id: String): String {
        if (id == "brave") return search("Android official documentation").take(250)
        val path = when (id) { "github" -> "/user"; "linkedin" -> "/v2/userinfo"; "slack" -> "/auth.test"; "telegram" -> "/getMe"; "notion" -> "/users/me"; else -> error("Unsupported") }
        val response = request(id, path)
        return "Connection responded: " + when (id) { "github" -> response.optString("login"); "telegram" -> response.optJSONObject("result")?.optString("username"); "linkedin" -> response.optString("name") + " · Person URN: urn:li:person:" + response.optString("sub"); "slack" -> response.optString("team"); else -> response.optString("name", "authorized") }
    }
    suspend fun search(query: String): String = request("brave", "/res/v1/web/search?q=" + java.net.URLEncoder.encode(query.take(500), "UTF-8") + "&count=5").optJSONObject("web")?.optJSONArray("results")?.toString()?.take(14000) ?: "No web results."
    suspend fun repositories(): String = request("github", "/user/repos?per_page=10&sort=updated").getJSONArray("items").let { array ->
        (0 until array.length()).joinToString("\n") { i -> array.getJSONObject(i).let { "${it.optString("full_name")}: ${it.optString("html_url")}" } }
    }
    suspend fun queue(service: String, title: String, text: String): String {
        require(service in setOf("linkedin", "github", "telegram", "slack", "notion")) { "Unsupported write action" }
        require(vault.get("connection_${service}_key").isNotBlank()) { "Connect $service first." }
        val target = vault.get("connection_${service}_target"); require(target.isNotBlank()) { "Set the $service destination in Settings." }
        val limit = when (service) { "telegram" -> 4096; "linkedin" -> 3000; "notion" -> 2000; else -> 10000 }
        require(text.length in 1..limit) { "$service needs between 1 and $limit characters. Shorten the draft." }
        val payload = JSONObject().put("target", target).put("title", title.take(200)).put("text", text)
        val existing = dao.pendingActions().firstOrNull { it.kind == service && vault.open(it.encryptedPayload) == payload.toString() }
        if (existing != null) return "NEEDS_APPROVAL: ${existing.id}. Identical action is already waiting in Tools; nothing sent."

        val action = PendingAction(kind = service, encryptedPayload = vault.seal(payload.toString()))
        dao.addAction(action)
        return "NEEDS_APPROVAL: ${action.id}. A $service action to $target is in the Tools review queue. Nothing has been sent."
    }
    suspend fun approve(id: String): String {
        val item = dao.action(id) ?: error("Action unavailable")
        require(ActionRules.canApprove(item.state, item.createdAt, System.currentTimeMillis())) { "Action is expired or already handled. Create a new proposal." }
        val p = JSONObject(vault.open(item.encryptedPayload)); val target = p.getString("target"); val text = p.getString("text"); val title = p.getString("title")
        require(target == vault.get("connection_${item.kind}_target")) { "Destination changed. Create a new proposal." }
        require(dao.claimAction(id) == 1) { "Action already handled" }
        return try {
            val result = when (item.kind) {
                "telegram" -> request("telegram", "/sendMessage", JSONObject().put("chat_id", target).put("text", text.take(4096)))
                "slack" -> request("slack", "/chat.postMessage", JSONObject().put("channel", target).put("text", text))
                "github" -> {
                    require(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+").matches(target)) { "Invalid repository" }
                    request("github", "/repos/$target/issues", JSONObject().put("title", title.ifBlank { "TARA note" }).put("body", text))
                }
                "linkedin" -> {
                    require(Regex("urn:li:person:[A-Za-z0-9_-]+").matches(target)) { "Invalid LinkedIn person URN" }
                    request("linkedin", "/rest/posts", JSONObject().put("author", target).put("commentary", text.take(3000)).put("visibility", "PUBLIC")
                        .put("distribution", JSONObject().put("feedDistribution", "MAIN_FEED").put("targetEntities", JSONArray()).put("thirdPartyDistributionChannels", JSONArray()))
                        .put("lifecycleState", "PUBLISHED").put("isReshareDisabledByAuthor", false))
                }
                "notion" -> request("notion", "/pages", JSONObject().put("parent", JSONObject().put("page_id", target))
                    .put("properties", JSONObject().put("title", JSONObject().put("title", JSONArray().put(JSONObject().put("text", JSONObject().put("content", title.ifBlank { "TARA note" }))))))
                    .put("children", JSONArray().put(JSONObject().put("object", "block").put("type", "paragraph").put("paragraph", JSONObject().put("rich_text", JSONArray().put(JSONObject().put("type", "text").put("text", JSONObject().put("content", text.take(2000)))))))))
                else -> error("Unknown action")
            }
            val ref = result.optString("provider_id").ifBlank { result.optString("id").ifBlank { result.optString("ts").ifBlank { result.optJSONObject("result")?.optString("message_id").orEmpty() } } }
            require(ref.isNotBlank()) { "Provider response had no receipt; verify in the service before trying again." }
            dao.finishAction(id, "SUCCESS", ref); dao.addAudit(Audit(action = item.kind, outcome = "SUCCESS", reference = ref))
            "Confirmed by ${item.kind}: $ref"
        } catch (e: Exception) {
            dao.finishAction(id, "CHECK_PROVIDER", "Delivery uncertain or rejected; inspect provider before creating another action.")
            throw e
        }
    }
}
