package ai.tara.personal

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.room.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Singleton

/** Sensitive columns and preferences are encrypted before they touch disk; metadata is not. */
@Singleton
class Vault @javax.inject.Inject constructor(@ApplicationContext private val context: Context) {
    private val alias = "tara-local-v1"
    private val key by lazy {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? javax.crypto.SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
            generateKey()
        }
    }
    fun seal(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        return Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }
    fun open(encoded: String): String {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12))) }
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }
    private val prefs = context.getSharedPreferences("secrets", Context.MODE_PRIVATE)
    fun put(name: String, value: String) { prefs.edit().putString(name, seal(value)).apply() }
    fun get(name: String): String = prefs.getString(name, null)?.let { runCatching { open(it) }.getOrDefault("") } ?: ""
    fun clear() { prefs.edit().clear().commit() }
}

@Entity(tableName = "memories") data class Memory(@PrimaryKey(autoGenerate = true) val id: Long = 0, val category: String, val encryptedText: String, val createdAt: Long = System.currentTimeMillis())
@Entity(tableName = "turns") data class Turn(@PrimaryKey(autoGenerate = true) val id: Long = 0, val role: String, val encryptedText: String, val at: Long = System.currentTimeMillis())
@Entity(tableName = "documents") data class Document(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String, val uri: String, val mime: String, val createdAt: Long = System.currentTimeMillis())
@Entity(tableName = "applications", indices = [Index(value = ["fingerprint"], unique = true)])
data class ApplicationRecord(@PrimaryKey(autoGenerate = true) val id: Long = 0, val fingerprint: String, val recipient: String, val draftId: String, val messageId: String? = null, val at: Long = System.currentTimeMillis())
@Entity(tableName = "audit") data class Audit(@PrimaryKey(autoGenerate = true) val id: Long = 0, val action: String, val outcome: String, val reference: String = "", val at: Long = System.currentTimeMillis())
@Entity(tableName = "actions") data class PendingAction(@PrimaryKey val id: String = java.util.UUID.randomUUID().toString(), val kind: String, val encryptedPayload: String, val state: String = "PENDING", val createdAt: Long = System.currentTimeMillis(), val result: String = "")
@Dao interface TaraDao {
    @Insert suspend fun addAction(action: PendingAction)
    @Query("SELECT * FROM actions WHERE state = 'PENDING'") suspend fun pendingActions(): List<PendingAction>
    @Query("SELECT * FROM actions ORDER BY createdAt DESC LIMIT 100") fun actions(): Flow<List<PendingAction>>
    @Query("SELECT * FROM actions WHERE id = :id") suspend fun action(id: String): PendingAction?
    @Query("UPDATE actions SET state = 'RUNNING' WHERE id = :id AND state = 'PENDING'") suspend fun claimAction(id: String): Int
    @Query("UPDATE actions SET state = :state, result = :result WHERE id = :id") suspend fun finishAction(id: String, state: String, result: String)
    @Query("DELETE FROM actions") suspend fun clearActions()

    @Query("SELECT * FROM memories ORDER BY createdAt DESC") fun memories(): Flow<List<Memory>>
    @Query("SELECT * FROM memories ORDER BY createdAt DESC LIMIT 60") suspend fun recentMemories(): List<Memory>
    @Query("SELECT * FROM memories ORDER BY createdAt DESC") suspend fun allMemories(): List<Memory>
    @Query("SELECT * FROM turns ORDER BY at DESC LIMIT 20") suspend fun recentTurns(): List<Turn>
    @Query("SELECT * FROM turns ORDER BY at DESC LIMIT 80") fun turns(): Flow<List<Turn>>
    @Query("SELECT * FROM documents ORDER BY createdAt DESC") fun documents(): Flow<List<Document>>
    @Query("SELECT * FROM applications WHERE fingerprint = :fingerprint LIMIT 1") suspend fun application(fingerprint: String): ApplicationRecord?
    @Insert suspend fun addMemory(memory: Memory)
    @Insert suspend fun addTurn(turn: Turn)
    @Insert suspend fun addDocument(document: Document)
    @Insert suspend fun addApplication(record: ApplicationRecord)
    @Insert suspend fun addAudit(audit: Audit)
    @Query("DELETE FROM memories WHERE id = :id") suspend fun deleteMemory(id: Long)
    @Query("DELETE FROM memories") suspend fun clearMemories()
    @Query("DELETE FROM turns") suspend fun clearTurns()
    @Query("DELETE FROM documents") suspend fun clearDocuments()
    @Query("DELETE FROM applications") suspend fun clearApplications()
    @Query("DELETE FROM audit") suspend fun clearAudit()
}
@Database(entities = [Memory::class, Turn::class, Document::class, ApplicationRecord::class, Audit::class, PendingAction::class], version = 2, exportSchema = false)
abstract class TaraDatabase : RoomDatabase() { abstract fun dao(): TaraDao }
@Module @InstallIn(SingletonComponent::class) object TaraModule {
    @Provides @Singleton fun database(@ApplicationContext context: Context): TaraDatabase = Room.databaseBuilder(context, TaraDatabase::class.java, "tara.db").addMigrations(object : androidx.room.migration.Migration(1, 2) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS actions (id TEXT NOT NULL PRIMARY KEY, kind TEXT NOT NULL, encryptedPayload TEXT NOT NULL, state TEXT NOT NULL, createdAt INTEGER NOT NULL, result TEXT NOT NULL)")
        }
    }).build()
    @Provides fun dao(db: TaraDatabase): TaraDao = db.dao()
    @Provides @Singleton fun http(): okhttp3.OkHttpClient = okhttp3.OkHttpClient.Builder().callTimeout(java.time.Duration.ofSeconds(35)).build()
}
