package ai.tara.personal

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.zip.ZipInputStream

object VoiceModel {
    const val folder = "vosk-model-small-en-us-0.15"
    fun path(context: Context) = File(context.filesDir, folder)
    fun ready(context: Context) = File(path(context), "am/final.mdl").isFile && File(path(context), "graph/Gr.fst").isFile
    suspend fun install(context: Context) = withContext(Dispatchers.IO) {
        if (ready(context)) return@withContext
        val zip = File(context.cacheDir, "voice-download.zip")
        val staging = File(context.filesDir, "voice-staging").apply { deleteRecursively(); mkdirs() }
        try {
            val http = OkHttpClient.Builder().callTimeout(java.time.Duration.ofMinutes(5)).build()
            http.newCall(Request.Builder().url("https://alphacephei.com/vosk/models/$folder.zip").build()).await().use { response ->
                require(response.isSuccessful) { "Voice model download failed (${response.code})" }
                response.body!!.byteStream().use { input -> zip.outputStream().use { output ->
                    val buffer = ByteArray(32768); var total = 0L
                    while (true) { coroutineContext.ensureActive(); val count = input.read(buffer); if (count < 0) break; total += count; require(total <= 80_000_000) { "Download too large" }; output.write(buffer, 0, count) }
                } }
            }
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            zip.inputStream().use { input -> val buffer = ByteArray(32768); while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) } }
            require(digest.digest().joinToString("") { "%02x".format(it) } == "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498") { "Voice model integrity check failed" }
            ZipInputStream(zip.inputStream().buffered()).use { stream ->
                var size = 0L
                while (true) {
                    coroutineContext.ensureActive()
                    val entry = stream.nextEntry ?: break
                    val file = File(staging, entry.name)
                    require(file.canonicalPath.startsWith(staging.canonicalPath + File.separator)) { "Unsafe model archive" }
                    if (entry.isDirectory) file.mkdirs() else { file.parentFile!!.mkdirs(); file.outputStream().use { output ->
                        val buffer = ByteArray(32768)
                        while (true) { val count = stream.read(buffer); if (count < 0) break; size += count; require(size < 200_000_000) { "Model expansion limit" }; output.write(buffer, 0, count) }
                    } }
                }
            }
            require(File(staging, "$folder/am/final.mdl").isFile) { "Invalid model archive" }
            path(context).deleteRecursively()
            require(File(staging, folder).renameTo(path(context))) { "Unable to install model" }
        } finally { zip.delete(); staging.deleteRecursively() }
    }
}
