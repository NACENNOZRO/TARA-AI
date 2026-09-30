package ai.tara.personal

import android.app.*
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.*
import java.util.concurrent.TimeUnit

class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val manager = NotificationManagerCompat.from(applicationContext)
        if (!manager.areNotificationsEnabled()) return Result.failure()
        val text = runCatching { Vault(applicationContext).open(inputData.getString("text") ?: return Result.failure()) }.getOrElse { return Result.failure() }
        manager.createNotificationChannel(NotificationChannel("reminders", "Tara reminders", NotificationManager.IMPORTANCE_DEFAULT))
        val intent = PendingIntent.getActivity(applicationContext, 0, android.content.Intent(applicationContext, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        manager.notify(id.hashCode(), NotificationCompat.Builder(applicationContext, "reminders").setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("TARA reminder").setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text)).setContentIntent(intent)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setAutoCancel(true).build())
        return Result.success()
    }
    companion object {
        fun schedule(context: Context, minutes: Long, encrypted: String) {
            require(NotificationManagerCompat.from(context).areNotificationsEnabled()) { "Enable TARA notifications in Android settings first." }
            WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<ReminderWorker>().setInitialDelay(minutes, TimeUnit.MINUTES)
                .setInputData(workDataOf("text" to encrypted)).addTag("tara-reminder").build())
        }
    }
}
