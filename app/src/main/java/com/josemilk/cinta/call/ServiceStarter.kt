package com.josemilk.cinta.call

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat
import com.josemilk.cinta.data.Vault

fun Context.granted(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

fun Context.isDefaultDialer(): Boolean {
    val telecom = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
    return telecom.defaultDialerPackage == packageName
}

object ServiceStarter {
    fun sync(context: Context) {
        val app = context.applicationContext
        val settings = Vault.get(app).snapshot().settings
        val intent = Intent(app, AnsweringService::class.java)
        val allowed = settings.enabled && settings.watchCalls && app.granted(Manifest.permission.READ_PHONE_STATE)
        if (!allowed) {
            app.stopService(intent)
            return
        }
        if (Build.VERSION.SDK_INT >= 34 && !app.granted(Manifest.permission.RECORD_AUDIO)) {
            // El vigilante arranca igual: el micrófono se pide otra vez al grabar.
        }
        try {
            ContextCompat.startForegroundService(app, intent)
        } catch (_: Exception) {
        }
    }
}
