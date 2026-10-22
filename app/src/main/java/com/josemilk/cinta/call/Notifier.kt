package com.josemilk.cinta.call

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.josemilk.cinta.MainActivity
import com.josemilk.cinta.R

object Notifier {
    const val WATCH_CHANNEL = "cinta_watch"
    const val CALL_CHANNEL = "cinta_call"
    const val MAIL_CHANNEL = "cinta_mail"
    const val WATCH_ID = 7
    const val CALL_ID = 42

    fun ensure(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(WATCH_CHANNEL, context.getString(R.string.channel_watch), NotificationManager.IMPORTANCE_LOW),
        )
        val calls = NotificationChannel(CALL_CHANNEL, context.getString(R.string.channel_call), NotificationManager.IMPORTANCE_HIGH)
        calls.lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        manager.createNotificationChannel(calls)
        val mail = NotificationChannel(MAIL_CHANNEL, context.getString(R.string.channel_mail), NotificationManager.IMPORTANCE_DEFAULT)
        manager.createNotificationChannel(mail)
    }

    fun message(context: Context, caller: String) {
        ensure(context)
        val intent = PendingIntent.getActivity(
            context,
            3,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, MAIL_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Nuevo mensaje")
            .setContentText(caller.ifBlank { "Desconocido" })
            .setAutoCancel(true)
            .setContentIntent(intent)
            .build()
        val id = 1000 + (caller.hashCode() and 0xfff)
        context.getSystemService(NotificationManager::class.java).notify(id, notification)
    }
}
