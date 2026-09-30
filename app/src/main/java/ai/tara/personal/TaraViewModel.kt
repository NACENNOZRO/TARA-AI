package ai.tara.personal

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject

@HiltViewModel class TaraViewModel @Inject constructor(
    private val dao: TaraDao, private val vault: Vault, private val ai: AiClient, private val workflow: Workflow, private val runner: AgentRunner, private val integrations: Integrations
): ViewModel() {
    val turns = dao.turns().map { rows -> rows.reversed().map { it.role to runCatching { vault.open(it.encryptedText) }.getOrDefault("[unavailable]") } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val memories = dao.memories().map { rows -> rows.map { it.id to runCatching { vault.open(it.encryptedText) }.getOrDefault("[unavailable]") } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val documents = dao.documents().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val status = runner.status
    val preview = MutableStateFlow<EmailPreview?>(null)
    private var running: Job? = null
    private var draftInProgress = false
    fun key() = vault.get("api_key")
    fun model() = vault.get("model")
    fun saveKey(key: String, model: String) { vault.put("api_key", key.trim()); vault.put("model", model.trim()); status.value = "AI settings saved locally" }
    fun stop() { running?.cancel(); running = null; status.value = "Stopped" }
    fun say(input: String, context: Context) {
        if (input.isBlank()) return
        stop()
        running = viewModelScope.launch {
            try { runner.run(input) }
            catch (e: CancellationException) { status.value = "Stopped"; throw e }
            catch (e: Exception) { status.value = e.message?.take(200) ?: "Task failed" }
        }
    }
    val actions = dao.actions().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    fun payload(item: PendingAction) = runCatching { vault.open(item.encryptedPayload) }.getOrDefault("Unavailable")
    fun approve(id: String) = viewModelScope.launch {
        status.value = "Performing approved action"
        try { status.value = integrations.approve(id) } catch (e: Exception) { status.value = e.message?.take(200) ?: "Action failed" }
    }
    fun reject(id: String) = viewModelScope.launch { dao.action(id)?.let { if (it.state == "PENDING") dao.finishAction(id, "REJECTED", "User rejected") } }
    fun setting(name: String) = vault.get(name)
    fun setting(name: String, value: String) = vault.put(name, value)
    fun provider() = ai.selected()
    fun providerValue(id: String, field: String) = ai.value(id, field)
    fun saveProvider(id: String, base: String, model: String, key: String, activate: Boolean = false) = ai.save(id, base, model, key, activate)
    suspend fun testModel(id: String) = ai.testModel(id)
    suspend fun listModels(id: String) = ai.models(id)
    suspend fun testConnection(id: String) = integrations.test(id)
    fun saveMemory(value: String) = viewModelScope.launch { if (value.isNotBlank()) dao.addMemory(Memory(category = "manual", encryptedText = vault.seal(value.take(1500)))) }
    fun deleteMemory(id: Long) = viewModelScope.launch { dao.deleteMemory(id) }
    fun eraseAll() = viewModelScope.launch { stop(); dao.clearActions(); dao.clearMemories(); dao.clearTurns(); dao.clearDocuments(); dao.clearApplications(); dao.clearAudit(); vault.clear(); status.value = "Local data deleted" }
    fun importResume(context: Context, uri: android.net.Uri) = viewModelScope.launch {
        runCatching { workflow.selectFile(context, uri) }.onSuccess { status.value = "Resume imported" }.onFailure { status.value = "Import failed: ${it.message}" }
    }
    fun prepare(job: String, recipient: String, document: Document?, context: Context) {
        stop()
        running = viewModelScope.launch {
            try {
                require(job.isNotBlank()) { "Add job details." }
                status.value = "Preparing email for review"
                val resume = document?.let { workflow.readFile(context, it) } ?: "No resume selected"
                val result = ai.composeApplication(job, resume)
                preview.value = EmailPreview(recipient, result.first, result.second, job, document)
                status.value = "Review recipient, text and attachment"
            } catch (e: Exception) { status.value = "Preparation failed: ${e.message?.take(120)}" }
        }
    }
    fun setPreview(value: EmailPreview) { preview.value = value }
    fun createDraft(token: String, context: Context) = viewModelScope.launch {
        if (draftInProgress) return@launch
        val current = preview.value ?: return@launch
        draftInProgress = true
        status.value = "Creating Gmail draft"
        try {
            val id = workflow.createDraft(current, token, context)
            status.value = "Gmail draft confirmed: $id"
            preview.value = null
        } catch (e: Exception) { status.value = "Draft failed: ${e.message?.take(120)}" }
        finally { draftInProgress = false }
    }
    fun exportMemories(context: Context, uri: android.net.Uri) = viewModelScope.launch {
        runCatching {
            val data = dao.allMemories().joinToString("\n") { vault.open(it.encryptedText) }
            context.contentResolver.openOutputStream(uri)?.use { it.write(data.toByteArray()) } ?: error("Export unavailable")
        }.onSuccess { status.value = "Memory exported" }.onFailure { status.value = "Export failed: ${it.message}" }
    }
}
