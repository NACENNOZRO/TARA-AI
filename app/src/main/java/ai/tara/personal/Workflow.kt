package ai.tara.personal

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import javax.inject.Inject

/** User-controlled outgoing preview. No model-provided tool call can send it. */
data class EmailPreview(val recipient: String = "", val subject: String = "", val body: String = "", val job: String = "", val document: Document? = null)
class Workflow @Inject constructor(private val dao: TaraDao, private val vault: Vault, private val http: OkHttpClient) {
    suspend fun selectFile(context: Context, uri: Uri): Document = withContext(Dispatchers.IO) {
        val mime = context.contentResolver.getType(uri).orEmpty()
        require(mime == "application/pdf" || mime == "text/plain") { "Choose a PDF or plain text resume." }
        context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "Resume"
        Document(name = name, uri = uri.toString(), mime = mime).also { dao.addDocument(it) }
    }
    suspend fun readFile(context: Context, doc: Document): String = withContext(Dispatchers.IO) {
        val input = context.contentResolver.openInputStream(Uri.parse(doc.uri)) ?: error("Selected document unavailable")
        val data = input.use { it.readNBytes(2_000_001) }
        require(data.size <= 2_000_000) { "Document exceeds 2 MB limit." }
        if (doc.mime == "text/plain") String(data, Charsets.UTF_8).take(14_000)
        else {
            // Android's PdfRenderer renders images; it does not extract text.
            "PDF attached: ${doc.name}. Enter relevant qualifications in the job details if you want tailored text."
        }
    }
    suspend fun publicJob(url: String): String = withContext(Dispatchers.IO) {
        val parsed = url.toHttpUrlOrNull() ?: error("Invalid URL")
        require(parsed.scheme == "https" && parsed.host !in setOf("localhost", "127.0.0.1") && !parsed.host.endsWith(".local")) { "A public HTTPS URL is required." }
        // Avoid untrusted redirect targets and private-network fetching.
        val safeDns = object : okhttp3.Dns {
            override fun lookup(hostname: String): List<java.net.InetAddress> = java.net.InetAddress.getAllByName(hostname).toList().also { addresses ->
                require(addresses.isNotEmpty() && addresses.all { address ->
                    val b = address.address.map { it.toInt() and 255 }
                    !address.isAnyLocalAddress && !address.isLoopbackAddress && !address.isLinkLocalAddress && !address.isSiteLocalAddress &&
                        !address.isMulticastAddress && !(b.size == 4 && (b[0] == 0 || b[0] == 100 && b[1] in 64..127 || b[0] == 169 && b[1] == 254))
                }) { "Private network addresses are not supported." }
            }
        }
        val request = Request.Builder().url(parsed).get().build()
        http.newBuilder().dns(safeDns).followRedirects(false).build().newCall(request).execute().use { response ->
            require(response.isSuccessful && response.header("Content-Type", "").orEmpty().contains("text/html")) { "Link is inaccessible or not an HTML page." }
            val html = response.body?.charStream()?.use { reader ->
                val buffer = CharArray(80_000); var size = 0
                while (size < buffer.size) { val n = reader.read(buffer, size, buffer.size - size); if (n < 0) break; size += n }
                String(buffer, 0, size)
            }.orEmpty()
            html.replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ").replace(Regex("<[^>]+>"), " ")
                .replace(Regex("\\s+"), " ").take(10_000)
        }
    }
    suspend fun createDraft(preview: EmailPreview, accessToken: String, context: Context): String = withContext(Dispatchers.IO) {
        require(Policy.validRecipient(preview.recipient)) { "Check recipient email." }
        require(preview.subject.isNotBlank() && preview.body.isNotBlank()) { "Subject and body are required." }
        val fingerprint = sha256(preview.recipient.lowercase() + "|" + preview.job.lowercase())
        require(dao.application(fingerprint) == null) { "A draft for this recipient and job has already been created." }
        val boundary = "tara_${java.util.UUID.randomUUID()}"
        val mime = buildString {
            append("To: ${preview.recipient}\r\n")
            append("Subject: ${header(preview.subject)}\r\nMIME-Version: 1.0\r\n")
            append("Content-Type: multipart/mixed; boundary=\"$boundary\"\r\n\r\n")
            append("--$boundary\r\nContent-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: base64\r\n\r\n")
            append(Base64.encodeToString(preview.body.toByteArray(), Base64.NO_WRAP)); append("\r\n")
            preview.document?.let { doc ->
                val bytes = context.contentResolver.openInputStream(Uri.parse(doc.uri))?.use { it.readNBytes(2_000_001) } ?: error("Resume grant lost")
                require(bytes.size <= 2_000_000) { "Resume exceeds 2 MB limit." }
                append("--$boundary\r\nContent-Type: ${doc.mime}\r\nContent-Disposition: attachment; filename=\"resume.${if (doc.mime == "application/pdf") "pdf" else "txt"}\"\r\nContent-Transfer-Encoding: base64\r\n\r\n")
                append(Base64.encodeToString(bytes, Base64.NO_WRAP)); append("\r\n")
            }
            append("--$boundary--\r\n")
        }
        val raw = Base64.encodeToString(mime.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val payload = JSONObject().put("message", JSONObject().put("raw", raw))
        val request = Request.Builder().url("https://gmail.googleapis.com/gmail/v1/users/me/drafts")
            .header("Authorization", "Bearer $accessToken")
            .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
        http.newCall(request).execute().use { response ->
            val result = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("Gmail draft failed (${response.code}): check Gmail authorization and account permissions.")
            val id = JSONObject(result).getString("id")
            dao.addApplication(ApplicationRecord(fingerprint = fingerprint, recipient = preview.recipient, draftId = id))
            dao.addAudit(Audit(action = "gmail.drafts.create", outcome = "SUCCESS", reference = id))
            id
        }
    }
    private fun header(text: String) = "=?UTF-8?B?" + Base64.encodeToString(text.replace(Regex("[\\r\\n]"), " ").toByteArray(), Base64.NO_WRAP) + "?="
    private fun sha256(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
