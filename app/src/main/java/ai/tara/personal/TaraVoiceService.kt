package ai.tara.personal

import android.app.*
import android.content.Intent
import android.os.*
import android.speech.*
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.SpeechService
import java.util.Locale
import javax.inject.Inject

object VoiceState { val status = MutableStateFlow("Off"); val active = MutableStateFlow(false) }
@AndroidEntryPoint class TaraVoiceService : Service() {
    @Inject lateinit var runner: AgentRunner
    @Inject lateinit var vault: Vault
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var audio: SpeechService? = null
    private var platform: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var readyToSpeak = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var commandTimer: Job? = null
    private var phase = "loading"
    private var generation = 0
    private var afterSpeech: (() -> Unit)? = null
    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("tara_voice", "Tara voice listening", NotificationManager.IMPORTANCE_LOW))
        tts = TextToSpeech(this) { status -> readyToSpeak = status == TextToSpeech.SUCCESS }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { scope.launch { afterSpeech?.also { afterSpeech = null; it() } } }
            @Deprecated("Platform callback") override fun onError(id: String?) { onDone(id) }
        })
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { stopSelf(); return START_NOT_STICKY }
        if (VoiceState.active.value) return START_NOT_STICKY
        try {
            val notification = notification("Starting local listener")
            if (Build.VERSION.SDK_INT >= 29) startForeground(71, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(71, notification)
            require(VoiceModel.ready(this)) { "Download the voice model in Settings first." }
            VoiceState.active.value = true
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tara:voice").apply { acquire(4 * 60 * 60 * 1000L) }
            scope.launch { delay(4 * 60 * 60 * 1000L); stopSelf() }
            scope.launch {
                try { model = withContext(Dispatchers.IO) { Model(VoiceModel.path(this@TaraVoiceService).absolutePath) }; startWake() }
                catch (e: Exception) { VoiceState.status.value = "Voice could not start: ${e.message?.take(100)}"; stopSelf() }
            }
        } catch (e: Exception) { VoiceState.status.value = e.message?.take(120) ?: "Microphone unavailable"; stopSelf() }
        return START_NOT_STICKY
    }
    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, TaraVoiceService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, "tara_voice").setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("TARA · microphone active")
            .setContentText(text).setContentIntent(open).setOngoing(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop listening", stop).build()
    }
    private fun state(text: String) { VoiceState.status.value = text; getSystemService(NotificationManager::class.java).notify(71, notification(text)) }
    private fun stopAudio() { generation++; audio?.stop(); audio?.shutdown(); audio = null; recognizer?.close(); recognizer = null; platform?.cancel(); platform?.destroy(); platform = null }
    private fun startWake() {
        if (!VoiceState.active.value) return
        stopAudio(); phase = "wake"; state("Say Tara · local listening")
        try {
            recognizer = Recognizer(model, 16000f, "[\"tara\", \"hey tara\", \"[unk]\"]")
            val epoch = generation
            audio = SpeechService(recognizer, 16000f).also { it.startListening(object : org.vosk.android.RecognitionListener {
                override fun onPartialResult(hypothesis: String?) { if (epoch == generation && phase == "wake" && Regex("\\btara\\b").containsMatchIn(text(hypothesis, "partial"))) awaken() }
                override fun onResult(hypothesis: String?) { if (epoch == generation && phase == "wake" && Regex("\\btara\\b").containsMatchIn(text(hypothesis, "text"))) awaken() }
                override fun onFinalResult(hypothesis: String?) {}
                override fun onError(e: Exception?) { if (epoch == generation) { state("Microphone error; restart listening in Settings"); stopSelf() } }
                override fun onTimeout() {}
            }) }
        } catch (e: Exception) { state("Wake detector failed: ${e.message?.take(80)}"); stopSelf() }
    }
    private fun awaken() {
        phase = "greeting"; stopAudio(); state("Tara heard you")
        speak(if (vault.get("language") == "ta-IN") "சொல்லு, நான் இங்க இருக்கேன்" else listOf("Yeah, I'm here", "I'm listening", "Sollu da macha").random()) { startCommand() }
    }
    private fun startCommand() {
        if (!VoiceState.active.value) return
        phase = "command"; state("Listening for your command")
        commandTimer?.cancel(); commandTimer = scope.launch { delay(18000); if (phase == "command") startWake() }
        if (vault.get("language") == "ta-IN") { startPlatformCommand(); return }
        try {
            recognizer = Recognizer(model, 16000f)
            val epoch = generation
            audio = SpeechService(recognizer, 16000f).also { it.startListening(object : org.vosk.android.RecognitionListener {
                override fun onPartialResult(hypothesis: String?) {}
                override fun onResult(hypothesis: String?) { if (epoch == generation) accept(text(hypothesis, "text")) }
                override fun onFinalResult(hypothesis: String?) { if (epoch == generation) accept(text(hypothesis, "text")) }
                override fun onError(e: Exception?) { if (epoch == generation) startWake() }
                override fun onTimeout() { if (epoch == generation) startWake() }
            }) }
        } catch (e: Exception) { startWake() }
    }
    private fun startPlatformCommand() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { speak("Tamil speech recognition is unavailable on this phone") { startWake() }; return }
        platform = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) { if (phase == "command") startWake() }
                override fun onResults(results: Bundle?) { accept(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()) }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM).putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ta-IN"))
        }
    }
    private fun accept(command: String) {
        if (phase != "command" || command.isBlank()) return
        commandTimer?.cancel(); phase = "working"; stopAudio()
        if (command.lowercase().trim() in setOf("stop listening", "stop tara", "stop")) { stopSelf(); return }
        state("Processing command")
        scope.launch {
            val reply = try { runner.run(command) } catch (e: CancellationException) { throw e } catch (e: Exception) { "I couldn't complete that. Check the app connection settings." }
            val locked = getSystemService(KeyguardManager::class.java).isDeviceLocked
            phase = "speaking"
            speak(if (locked && vault.get("speak_locked") != "true") "Your result is ready in Tara. Unlock your phone to review it." else reply.take(1800)) { startWake() }
        }
    }
    private fun speak(value: String, after: () -> Unit) {
        if (!readyToSpeak) { after(); return }
        tts?.language = Locale.forLanguageTag(vault.get("language").ifBlank { "en-IN" })
        tts?.setSpeechRate(vault.get("voice_rate").toFloatOrNull()?.coerceIn(0.5f, 1.5f) ?: 1f)
        afterSpeech = after
        if (tts?.speak(value, TextToSpeech.QUEUE_FLUSH, null, "tara-${System.nanoTime()}") == TextToSpeech.ERROR) { afterSpeech = null; after() }
    }
    private fun text(raw: String?, key: String) = runCatching { JSONObject(raw.orEmpty()).optString(key).lowercase() }.getOrDefault("")
    override fun onDestroy() {
        VoiceState.active.value = false; phase = "stopped"; stopAudio(); scope.cancel(); tts?.stop(); tts?.shutdown(); model?.close(); model = null
        if (wakeLock?.isHeld == true) wakeLock?.release()
        if (!VoiceState.status.value.contains("error", true) && !VoiceState.status.value.contains("failed", true)) VoiceState.status.value = "Off"
        super.onDestroy()
    }
}
