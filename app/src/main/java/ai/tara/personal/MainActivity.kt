package ai.tara.personal

import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.core.content.ContextCompat


@AndroidEntryPoint class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private val vm: TaraViewModel by viewModels()
    private var tts: TextToSpeech? = null
    private val mic = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (spoken != null) vm.say(spoken, this)
    }
    private val chooseFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.importResume(this, uri) }
    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri -> if (uri != null) vm.exportMemories(this, uri) }
    private val gmailConsent = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == RESULT_OK) runCatching {
            val token = Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(result.data).accessToken
            if (token != null) vm.createDraft(token, this) else vm.status.value = "Gmail did not return an access token"
        }.onFailure { vm.status.value = "Gmail authorization failed: ${it.message}" }
    }
    private var pendingBackground = false
    private val voicePermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            if (pendingBackground) startBackground() else listen()
        } else vm.status.value = "Microphone permission denied; typed commands still work."
    }
    private fun startBackground() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            pendingBackground = true
            voicePermissions.launch(if (android.os.Build.VERSION.SDK_INT >= 33) arrayOf(android.Manifest.permission.RECORD_AUDIO, android.Manifest.permission.POST_NOTIFICATIONS) else arrayOf(android.Manifest.permission.RECORD_AUDIO))
            return
        }
        if (android.os.Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 81)
        runCatching { ContextCompat.startForegroundService(this, Intent(this, TaraVoiceService::class.java)) }.onFailure { vm.status.value = it.message ?: "Could not start voice" }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)
        val shared = if (intent?.action == Intent.ACTION_SEND) intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty() else ""
        setContent { TaraScreen(vm, shared, onListen = { listen() }, onStop = { vm.stop(); tts?.stop() }, onSpeak = { speak(it) },
            onSelect = { chooseFile.launch(arrayOf("application/pdf", "text/plain")) },
            onExport = { export.launch("tara-memory.txt") }, onAuthorize = { authorizeGmail() }, onBackground = { startBackground() }, onVoiceStop = { stopService(Intent(this, TaraVoiceService::class.java)) }) }
    }
    override fun onInit(status: Int) { if (status == TextToSpeech.SUCCESS) tts?.language = Locale.getDefault() }
    private fun speak(text: String) { tts?.language = Locale.forLanguageTag(vm.setting("language").ifBlank { "en-IN" }); tts?.setSpeechRate(vm.setting("voice_rate").toFloatOrNull() ?: 1f); tts?.stop(); tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tara") }
    private fun listen() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            pendingBackground = false; voicePermissions.launch(arrayOf(android.Manifest.permission.RECORD_AUDIO)); return
        }
        stopService(Intent(this, TaraVoiceService::class.java))
        tts?.stop()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, vm.setting("language").ifBlank { "en-IN" })
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Tara is listening")
        }
        runCatching { mic.launch(intent) }.onFailure { vm.status.value = "Speech recognition unavailable; type your message." }
    }
    private fun authorizeGmail() {
        val request = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope("https://www.googleapis.com/auth/gmail.compose"))).build()
        Identity.getAuthorizationClient(this).authorize(request)
            .addOnSuccessListener { result ->
                if (result.hasResolution()) {
                    val pending = result.pendingIntent
                    if (pending != null) gmailConsent.launch(IntentSenderRequest.Builder(pending.intentSender).build())
                } else {
                    val token = result.accessToken
                    if (token != null) vm.createDraft(token, this) else vm.status.value = "No Gmail access token"
                }
            }.addOnFailureListener { vm.status.value = "Gmail authorization unavailable: ${it.message}" }
    }
    override fun onDestroy() { tts?.shutdown(); super.onDestroy() }
}

@Composable private fun TaraScreen(vm: TaraViewModel, shared: String, onListen: () -> Unit, onStop: () -> Unit, onSpeak: (String) -> Unit,
    onSelect: () -> Unit, onExport: () -> Unit, onAuthorize: () -> Unit, onBackground: () -> Unit, onVoiceStop: () -> Unit) {
    var tab by remember { mutableStateOf(0) }
    val status by vm.status.collectAsStateWithLifecycle()
    MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF77E2D0), secondary = Color(0xFFB6B4FF), background = Color(0xFF0D1423), surface = Color(0xFF141F32))) {
        Scaffold(topBar = { Surface(shadowElevation = 3.dp) { Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("TARA AI", style = MaterialTheme.typography.headlineSmall)
            Text(status, style = MaterialTheme.typography.bodySmall)
        } } }, bottomBar = {
            NavigationBar {
                listOf("Chat", "Tools", "Memory", "Settings").forEachIndexed { index, title ->
                    NavigationBarItem(selected = tab == index, onClick = { tab = index }, icon = { Text(listOf("◉", "✉", "◆", "⚙")[index]) }, label = { Text(title) })
                }
            }
        }) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) { when (tab) {
                0 -> ChatTab(vm, onListen, onStop, onSpeak)
                1 -> ToolsTab(vm, shared, onSelect, onAuthorize)
                2 -> MemoryTab(vm, onExport)
                3 -> SettingsTab(vm, onBackground, onVoiceStop)
            } }
        }
    }
}
@Composable private fun ChatTab(vm: TaraViewModel, onListen: () -> Unit, onStop: () -> Unit, onSpeak: (String) -> Unit) {
    val messages by vm.turns.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        LazyColumn(Modifier.weight(1f)) {
            item { Text("Hey Shushaanth. What are we doing today?", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(12.dp)) }
            items(messages) { (role, content) -> Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Column(Modifier.padding(12.dp)) { Text(if (role == "user") "You" else "Tara", style = MaterialTheme.typography.labelSmall)
                    Text(content)
                    if (role != "user") TextButton(onClick = { onSpeak(content) }) { Text("Speak") }
                }
            } }
        }
        Row { OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("Tell Tara") }, modifier = Modifier.weight(1f))
            Button(onClick = { vm.say(text, context); text = "" }, enabled = text.isNotBlank()) { Text("Send") } }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onListen) { Text("🎙 Speak") }
            OutlinedButton(onClick = onStop) { Text("Stop") }
        }
    }
}
@Composable private fun ApplyTab(vm: TaraViewModel, shared: String, onSelect: () -> Unit, onAuthorize: () -> Unit) {
    val docs by vm.documents.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var job by remember { mutableStateOf(shared) }
    var recipient by remember { mutableStateOf("") }
    var chosen by remember { mutableStateOf<Long?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Internship application", style = MaterialTheme.typography.titleLarge)
        Text("Paste the job description or a shared posting. Confirm the employer address yourself.")
        OutlinedTextField(job, { job = it }, label = { Text("Job details") }, modifier = Modifier.fillMaxWidth(), minLines = 5)
        OutlinedTextField(recipient, { recipient = it }, label = { Text("Recipient email") }, modifier = Modifier.fillMaxWidth())
        OutlinedButton(onClick = onSelect) { Text("Import PDF or text resume") }
        docs.forEach { doc -> FilterChip(selected = chosen == doc.id, onClick = { chosen = doc.id }, label = { Text(doc.name) }) }
        Button(onClick = { vm.prepare(job, recipient, docs.firstOrNull { it.id == chosen }, context) }, enabled = job.isNotBlank()) { Text("Prepare email") }
        if (preview != null) {
            val p = preview!!
            HorizontalDivider()
            Text("Review before creating a Gmail draft", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(p.recipient, { vm.setPreview(p.copy(recipient = it)) }, label = { Text("To") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(p.subject, { vm.setPreview(p.copy(subject = it)) }, label = { Text("Subject") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(p.body, { vm.setPreview(p.copy(body = it)) }, label = { Text("Body") }, modifier = Modifier.fillMaxWidth(), minLines = 7)
            Text("Attachment: ${p.document?.name ?: "none"}")
            Text("Source: your pasted job description")
            Button(onClick = onAuthorize) { Text("Authorize Gmail and create draft") }
            Text("This creates a draft in Gmail; it does not send the email.")
        }
    }
}
@Composable private fun MemoryTab(vm: TaraViewModel, onExport: () -> Unit) {
    val memoryContext = LocalContext.current
    val entries by vm.memories.collectAsStateWithLifecycle()
    var memory by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Private memory", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(memory, { memory = it }, label = { Text("Save an approved fact") }, modifier = Modifier.fillMaxWidth())
        Row { Button(onClick = { vm.saveMemory(memory); memory = "" }, enabled = memory.isNotBlank()) { Text("Save") }
            TextButton(onClick = onExport) { Text("Export") } }
        LazyColumn(Modifier.weight(1f)) { items(entries) { (id, value) -> Row(Modifier.fillMaxWidth()) {
            Text(value, Modifier.weight(1f).padding(8.dp)); TextButton(onClick = { vm.deleteMemory(id) }) { Text("Delete") }
        } } }
        if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Delete all local data?") }, text = { Text("This deletes memories, conversations, resume references, application history and local API settings. It cannot be undone.") },
            confirmButton = { TextButton(onClick = { memoryContext.stopService(Intent(memoryContext, TaraVoiceService::class.java)); androidx.work.WorkManager.getInstance(memoryContext).cancelAllWorkByTag("tara-reminder"); vm.eraseAll(); confirm = false }) { Text("Delete all") } }, dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } })
        OutlinedButton(onClick = { confirm = true }) { Text("Erase local data") }
    }
}
@Composable private fun ToolsTab(vm: TaraViewModel, shared: String, onSelect: () -> Unit, onAuthorize: () -> Unit) {
    var email by remember { mutableStateOf(false) }
    val actions by vm.actions.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<PendingAction?>(null) }
    val context = LocalContext.current
    if (email) {
        Column { TextButton(onClick = { email = false }) { Text("← All tools") }; Box(Modifier.weight(1f)) { ApplyTab(vm, shared, onSelect, onAuthorize) } }
        return
    }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Your assistant toolkit", style = MaterialTheme.typography.headlineSmall) }
        item { Text("Ask Tara to research, draft a LinkedIn post, create an issue, write notes, set a reminder, or check the weather. Connect services in Settings.") }
        item { OutlinedButton(onClick = { email = true }) { Text("Email & resume workspace") } }
        item { Text("Action review & history", style = MaterialTheme.typography.titleLarge) }
        if (actions.isEmpty()) item { Text("Proposed posts, messages and pages will appear here before they are sent.") }
        items(actions, key = { it.id }) { action -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
            Text(action.kind.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleMedium)
            Text(action.state, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(vm.payload(action), style = MaterialTheme.typography.bodySmall)
            if (action.result.isNotBlank()) Text(action.result)
            if (action.state == "PENDING") Row {
                Button(onClick = { selected = action }) { Text("Review") }
                TextButton(onClick = { vm.reject(action.id) }) { Text("Reject") }
            }
            if (action.state == "RUNNING") Text("If the app was interrupted, check the provider before creating another action.")
        } } }
    }
    selected?.let { action -> AlertDialog(onDismissRequest = { selected = null }, title = { Text("Confirm ${action.kind} action") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(vm.payload(action)); if (action.kind == "linkedin") Text("This publishes publicly on LinkedIn.") } },
        confirmButton = { TextButton(onClick = {
            if (context.getSystemService(android.app.KeyguardManager::class.java).isDeviceLocked) vm.status.value = "Unlock the phone to approve."
            else vm.approve(action.id)
            selected = null
        }) { Text("Approve & execute") } }, dismissButton = { TextButton(onClick = { selected = null }) { Text("Cancel") } }) }
}

@Composable private fun SettingsTab(vm: TaraViewModel, onBackground: () -> Unit, onVoiceStop: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var providerId by remember { mutableStateOf(vm.provider()) }
    var base by remember(providerId) { mutableStateOf(vm.providerValue(providerId, "base")) }
    var model by remember(providerId) { mutableStateOf(vm.providerValue(providerId, "model")) }
    var key by remember(providerId) { mutableStateOf(vm.providerValue(providerId, "key")) }
    var models by remember(providerId) { mutableStateOf<List<String>>(emptyList()) }
    var freeOnly by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var primary by remember { mutableStateOf(vm.provider()) }
    var fallback by remember { mutableStateOf(vm.setting("ollama_fallback") == "true") }
    var tools by remember { mutableStateOf(vm.setting("tools_enabled") != "false") }
    var connection by remember { mutableStateOf("linkedin") }
    var token by remember(connection) { mutableStateOf(vm.setting("connection_${connection}_key")) }
    var target by remember(connection) { mutableStateOf(vm.setting("connection_${connection}_target")) }
    var version by remember { mutableStateOf(vm.setting("linkedin_version").ifBlank { "202609" }) }
    var language by remember { mutableStateOf(vm.setting("language").ifBlank { "en-IN" }) }
    var personality by remember { mutableStateOf(vm.setting("personality").ifBlank { "Friendly, humorous, supportive; talk naturally in English, Tamil or Tanglish." }) }
    var rate by remember { mutableStateOf(vm.setting("voice_rate").toFloatOrNull() ?: 1f) }
    var speakLocked by remember { mutableStateOf(vm.setting("speak_locked") == "true") }
    var voiceReady by remember { mutableStateOf(VoiceModel.ready(context)) }
    val voiceActive by VoiceState.active.collectAsStateWithLifecycle()
    val voiceStatus by VoiceState.status.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Make Tara yours", style = MaterialTheme.typography.headlineMedium)
        Text("Engines, connections, voice and personality", color = MaterialTheme.colorScheme.primary)
        if (message.isNotBlank()) Card(Modifier.fillMaxWidth()) { Text(message, Modifier.padding(12.dp)) }
        Text("AI engines", style = MaterialTheme.typography.titleLarge)
        Text("Active: ${Providers.get(primary).label}. Free quotas and model availability belong to each provider; they can change.")
        Providers.all.chunked(2).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { row.forEach { p -> FilterChip(selected = providerId == p.id, onClick = { providerId = p.id }, label = { Text(p.label) }) } } }
        val provider = Providers.get(providerId)
        Text(provider.note)
        if (provider.signup.isNotBlank()) TextButton(onClick = { uriHandler.openUri(provider.signup) }) { Text("Get key / provider documentation ↗") }
        OutlinedTextField(base, { base = it }, label = { Text("API base URL") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        if (providerId != "ollama") OutlinedTextField(key, { key = it }, label = { Text("API key") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(model, { model = it }, label = { Text("Model ID / filter model list") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = { runCatching { vm.saveProvider(providerId, base, model, key, true) }.onSuccess { primary = providerId; message = "Provider selected" }.onFailure { message = it.message.orEmpty() } }) { Text("Save & use") }
            OutlinedButton(enabled = !busy, onClick = { scope.launch {
                busy = true
                try { vm.saveProvider(providerId, base, model, key); models = vm.listModels(providerId); message = "Loaded ${models.size} models" }
                catch (e: Exception) { message = e.message.orEmpty() } finally { busy = false }
            } }) { Text("Load models") }
        }
        if (providerId == "openrouter") Row { Checkbox(freeOnly, { freeOnly = it }); Text("Only free model IDs") }
        val shown = models.filter { (!freeOnly || providerId != "openrouter" || it.endsWith(":free") || it == "openrouter/free") && (model.isBlank() || it.contains(model, true)) }
        shown.take(12).forEach { id -> TextButton(onClick = { model = id }) { Text(id) } }
        if (models.isNotEmpty() && shown.isEmpty()) TextButton(onClick = { model = "" }) { Text("Clear model filter") }
        TextButton(enabled = !busy, onClick = { scope.launch { busy = true; try { vm.saveProvider(providerId, base, model, key); message = vm.testModel(providerId) } catch (e: Exception) { message = e.message.orEmpty() } finally { busy = false } } }) { Text("Test this model") }
        Row { Switch(fallback, { fallback = it; vm.setting("ollama_fallback", it.toString()) }); Text("Use local Ollama if cloud fails", Modifier.padding(10.dp)) }
        Row { Switch(tools, { tools = it; vm.setting("tools_enabled", it.toString()) }); Text("AI tool calling (model must support it)", Modifier.padding(10.dp)) }
        Text("Configure Ollama's local model with Load models before enabling fallback. Local requests stay on the phone; cloud requests share conversation/tool data with the selected provider. Models without tool support can still chat when tool calling is off.")
        HorizontalDivider()
        Text("Software connections", style = MaterialTheme.typography.titleLarge)
        Connections.all.chunked(3).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { row.forEach { spec -> FilterChip(selected = connection == spec.id, onClick = { connection = spec.id }, label = { Text(spec.name) }) } } }
        val spec = Connections.all.first { it.id == connection }
        Text(spec.help)
        TextButton(onClick = { uriHandler.openUri(spec.url) }) { Text("Open ${spec.name} setup ↗") }
        OutlinedTextField(token, { token = it }, label = { Text("Access token / API key") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), singleLine = true)
        if (connection != "brave") OutlinedTextField(target, { target = it }, label = { Text(spec.target) }, modifier = Modifier.fillMaxWidth())
        if (connection == "linkedin") OutlinedTextField(version, { version = it }, label = { Text("LinkedIn API version (YYYYMM)") })
        Row {
            Button(onClick = { vm.setting("connection_${connection}_key", token.trim()); vm.setting("connection_${connection}_target", target.trim()); vm.setting("linkedin_version", version.trim()); message = "Connection saved on this phone" }) { Text("Save") }
            TextButton(enabled = !busy, onClick = { scope.launch { busy = true; try { vm.setting("connection_${connection}_key", token.trim()); vm.setting("connection_${connection}_target", target.trim()); vm.setting("linkedin_version", version.trim()); message = vm.testConnection(connection) } catch (e: Exception) { message = e.message.orEmpty() } finally { busy = false } } }) { Text("Test") }
            TextButton(onClick = { token = ""; target = ""; vm.setting("connection_${connection}_key", ""); vm.setting("connection_${connection}_target", ""); message = "Removed local credentials; revoke the token at the provider too." }) { Text("Remove") }
        }
        Text("Gmail: connect through the Email workspace. Instagram/WhatsApp are not connected in this build; use their official business API requirements before adding adapters. Tokens are stored encrypted; they may expire and need replacement.")
        HorizontalDivider()
        Text("Hands-free voice", style = MaterialTheme.typography.titleLarge)
        Text(voiceStatus, color = MaterialTheme.colorScheme.primary)
        if (!voiceReady) Button(enabled = !busy, onClick = { scope.launch { busy = true; message = "Downloading local English voice model (about 40 MB)…"; try { VoiceModel.install(context); voiceReady = true; message = "Voice model installed" } catch (e: Exception) { message = e.message.orEmpty() } finally { busy = false } } }) { Text("Download local voice model") }
        Row { Button(enabled = voiceReady && !voiceActive, onClick = onBackground) { Text("Start listening") }; TextButton(onClick = onVoiceStop) { Text("Stop") } }
        Text("Start while this screen is open. Say ‘Tara’, wait for the greeting, then speak. A microphone notification stays visible. The session lasts up to 4 hours and can continue when the screen is off. Force-stop, reboot and some battery managers end it. Listening uses CPU and battery.")
        Text("English wake detection and commands use the local model. Tamil commands use your phone's speech service and may need internet. Tanglish recognition quality varies. There is no speaker authentication; keep spoken private answers disabled on the lock screen when others are nearby.")
        Row { Switch(speakLocked, { speakLocked = it; vm.setting("speak_locked", it.toString()) }); Text("Speak answers while phone is locked", Modifier.padding(10.dp)) }
        TextButton(onClick = { context.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${context.packageName}"))) }) { Text("Android battery & permission settings ↗") }
        HorizontalDivider()
        Text("Language & personality", style = MaterialTheme.typography.titleLarge)
        Row { listOf("en-IN" to "English / Tanglish", "ta-IN" to "Tamil").forEach { (id, label) -> FilterChip(selected = language == id, onClick = { language = id; vm.setting("language", id) }, label = { Text(label) }) } }
        Text("Speech speed: ${"%.1f".format(rate)}×")
        Slider(rate, { rate = it; vm.setting("voice_rate", it.toString()) }, valueRange = 0.5f..1.5f)
        OutlinedTextField(personality, { personality = it }, label = { Text("How Tara should talk") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
        Button(onClick = { vm.setting("personality", personality.take(1500)); message = "Personality saved" }) { Text("Save personality") }
    }
}
