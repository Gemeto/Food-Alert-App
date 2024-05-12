package id.gemeto.rasff.notifier.workers

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.room.Room
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import id.gemeto.rasff.notifier.ui.view.MainActivity
import id.gemeto.rasff.notifier.R
import id.gemeto.rasff.notifier.data.AppDatabase
import id.gemeto.rasff.notifier.data.CloudService
import id.gemeto.rasff.notifier.data.LastNotified
import id.gemeto.rasff.notifier.data.ktorClient
import java.util.UUID

class NotifierWorker(private val appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {
    private val notificationChannelId = "AndroidAlertsNotificationChannelId"
    private val cloudService = CloudService(ktorClient)

    override suspend fun doWork(): Result {
        val responseRSS = cloudService.getRSSArticles()
        val responseHTML = cloudService.getHTMLArticles()
        val db = Room.databaseBuilder(
            applicationContext,
            AppDatabase::class.java, "database-rasff-notifications"
        ).build()
        val lastNotifiedDao = db.lastNotifiedDao()
        val lastNotified: LastNotified? = lastNotifiedDao.getOne()
        if(lastNotified == null || lastNotified.firstItemTitle?.contains(responseRSS.items?.first()?.title.orEmpty()) == false || lastNotified.firstItemTitle?.contains(responseHTML.first().title) == false){
            if (ActivityCompat.checkSelfPermission(
                    appContext,
                    Manifest.permission.POST_NOTIFICATIONS,
                )
                == PackageManager.PERMISSION_GRANTED
            ) {
                with(NotificationManagerCompat.from(applicationContext)) {
                    notify(0, createNotification())
                }
                if(lastNotified != null) {
                    lastNotifiedDao.delete(lastNotified)
                }
                lastNotifiedDao.insert(LastNotified(UUID.randomUUID().toString(), responseRSS.items?.first()?.title.orEmpty() + responseHTML.first().title))
                return Result.success()
            }
            return Result.retry()
        }
        return Result.success()
    }

    private fun createNotification(): Notification {
        createNotificationChannel()

        val mainActivityIntent = Intent(applicationContext, MainActivity::class.java)

        val pendingIntentFlag = PendingIntent.FLAG_IMMUTABLE

        val mainActivityPendingIntent =
            PendingIntent.getActivity(
                applicationContext,
                0,
                mainActivityIntent,
                pendingIntentFlag,
            )

        return NotificationCompat.Builder(applicationContext, notificationChannelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentText("New Warnings Arrived!")
            .setContentIntent(mainActivityPendingIntent)
            .setAutoCancel(true)
            .build()
    }

    private fun createNotificationChannel() {
        val notificationChannel =
            NotificationChannel(
                notificationChannelId,
                "Sync Warnings",
                NotificationManager.IMPORTANCE_DEFAULT,
            )

        val notificationManager: NotificationManager? =
            ContextCompat.getSystemService(applicationContext, NotificationManager::class.java)

        notificationManager?.createNotificationChannel(notificationChannel)
    }
}