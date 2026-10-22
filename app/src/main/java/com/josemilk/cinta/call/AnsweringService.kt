package com.josemilk.cinta.call

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import com.josemilk.cinta.CallActivity
import com.josemilk.cinta.MainActivity
import com.josemilk.cinta.R
import com.josemilk.cinta.audio.Machine
import com.josemilk.cinta.data.Vault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

class AnsweringService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loop: Job? = null
    private var machineJob: Job? = null
    private var ringStarted = 0L
    private var ringToken = 0
    private var telephonyRinging = false
    private var telephonyStarted = 0L
    private var announcedFail = false
    private val stopMachine = AtomicBoolean(false)
    private var phoneListener: PhoneStateListener? = null
    private var modernCallback: TelephonyCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifier.ensure(this)
        listen()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            enterForeground(false)
        } catch (_: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (loop?.isActive != true) {
            loop = scope.launch {
                while (isActive) {
                    step()
                    delay(250)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        loop?.cancel()
        machineJob?.cancel()
        unlisten()
        scope.cancel()
        super.onDestroy()
    }

    fun promoteMicForeground() {
        if (!granted(Manifest.permission.RECORD_AUDIO)) return
        try {
            enterForeground(true)
        } catch (_: Exception) {
        }
    }

    private fun enterForeground(withMic: Boolean) {
        val text = MachineBus.phase.value.ifBlank {
            val rings = Vault.get(this).snapshot().settings.rings
            "Contestará después de $rings timbres"
        }
        val open = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, Notifier.WATCH_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Cinta está escuchando")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            if (withMic && granted(Manifest.permission.RECORD_AUDIO)) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            startForeground(Notifier.WATCH_ID, notification, type)
        } else {
            startForeground(Notifier.WATCH_ID, notification)
        }
    }

    private fun step() {
        val settings = Vault.get(this).snapshot().settings
        val call = CallHub.primary()
        val now = SystemClock.elapsedRealtime()
        if (!settings.enabled) {
            MachineBus.countdownSec.value = -1
            return
        }
        if (call != null && call.state == android.telecom.Call.STATE_RINGING) {
            if (ringToken != System.identityHashCode(call)) {
                ringToken = System.identityHashCode(call)
                ringStarted = now
                announcedFail = false
            }
            val need = settings.rings * RING_MS
            val left = ((need - (now - ringStarted)) / 1000L).toInt().coerceAtLeast(0)
            MachineBus.countdownSec.value = left
            if (now - ringStarted >= need && machineJob?.isActive != true && !MachineBus.running.value) {
                MachineBus.wantsMachine = true
                try {
                    call.answer(VideoProfile.STATE_AUDIO_ONLY)
                } catch (_: Exception) {
                    notifyFail()
                }
            }
        } else if (call == null && telephonyRinging) {
            if (telephonyStarted == 0L) telephonyStarted = now
            val need = settings.rings * RING_MS
            val left = ((need - (now - telephonyStarted)) / 1000L).toInt().coerceAtLeast(0)
            MachineBus.countdownSec.value = left
            if (now - telephonyStarted >= need && !announcedFail) {
                val accepted = tryAccept()
                if (!accepted) notifyFail()
            }
        } else {
            ringToken = 0
            ringStarted = 0L
            telephonyStarted = 0L
            if (machineJob?.isActive != true) MachineBus.countdownSec.value = -1
        }

        val active = call != null && call.state == android.telecom.Call.STATE_ACTIVE
        stopMachine.set(
            call == null ||
                call.state == android.telecom.Call.STATE_DISCONNECTED ||
                call.state == android.telecom.Call.STATE_DISCONNECTING,
        )
        if (active && MachineBus.wantsMachine && machineJob?.isActive != true) {
            startMachine(call!!)
        }
        if (now % 1000 < 280) {
            getSystemService(NotificationManager::class.java).notify(Notifier.WATCH_ID, currentNotification())
        }
    }

    private fun startMachine(call: android.telecom.Call) {
        stopMachine.set(false)
        val caller = callerLabel(call)
        machineJob = scope.launch {
            MachineBus.running.value = true
            MachineBus.phase.value = "Saludo"
            val saved = try {
                Machine.run(
                    context = this@AnsweringService,
                    caller = caller,
                    voiceCall = true,
                    shouldStop = { stopMachine.get() },
                    onPhase = { MachineBus.phase.value = it },
                    onPrepare = { CintaInCallService.earpiece() },
                    beforeRecord = { withContext(Dispatchers.Main) { promoteMicForeground() } },
                )
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            withContext(Dispatchers.Main) {
                if (call.state == android.telecom.Call.STATE_ACTIVE) {
                    try {
                        call.disconnect()
                    } catch (_: Exception) {
                    }
                }
            }
            if (saved) Notifier.message(this@AnsweringService, caller)
            MachineBus.running.value = false
            MachineBus.phase.value = ""
            MachineBus.wantsMachine = false
            MachineBus.countdownSec.value = -1
        }
    }

    private fun tryAccept(): Boolean {
        if (!granted(Manifest.permission.ANSWER_PHONE_CALLS) && !isDefaultDialer()) return false
        return try {
            val telecom = getSystemService(TelecomManager::class.java)
            @Suppress("DEPRECATION")
            telecom.acceptRingingCall()
            true
        } catch (_: SecurityException) {
            false
        }
    }

    private fun notifyFail() {
        if (announcedFail) return
        announcedFail = true
        Notifier.ensure(this)
        val open = PendingIntent.getActivity(
            this,
            8,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, Notifier.MAIL_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("No pude contestar")
            .setContentText("Pon a Cinta como aplicación de teléfono para descolgar.")
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        getSystemService(NotificationManager::class.java).notify(77, notification)
    }

    private fun currentNotification(): android.app.Notification {
        val phase = MachineBus.phase.value
        val count = MachineBus.countdownSec.value
        val text = when {
            phase.isNotBlank() -> phase
            count >= 0 -> "Contestará en ${count}s"
            else -> {
                val rings = Vault.get(this).snapshot().settings.rings
                "Contestará después de $rings timbres"
            }
        }
        val open = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, Notifier.WATCH_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Cinta está escuchando")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    @Suppress("DEPRECATION")
    private fun listen() {
        if (!granted(Manifest.permission.READ_PHONE_STATE)) return
        val telephony = getSystemService(TelephonyManager::class.java)
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) = onTelephony(state)
                }
                modernCallback = callback
                telephony.registerTelephonyCallback(mainExecutor, callback)
            } else {
                val listener = object : PhoneStateListener() {
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) = onTelephony(state)
                }
                phoneListener = listener
                telephony.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
            }
        } catch (_: SecurityException) {
        }
    }

    @Suppress("DEPRECATION")
    private fun unlisten() {
        val telephony = getSystemService(TelephonyManager::class.java)
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                modernCallback?.let { telephony.unregisterTelephonyCallback(it) }
            } else {
                phoneListener?.let { telephony.listen(it, PhoneStateListener.LISTEN_NONE) }
            }
        } catch (_: Exception) {
        }
    }

    private fun onTelephony(state: Int) {
        telephonyRinging = state == TelephonyManager.CALL_STATE_RINGING
        if (!telephonyRinging) {
            telephonyStarted = 0L
            announcedFail = false
        }
    }

    companion object {
        private const val RING_MS = 5_000L

        fun callerLabel(call: android.telecom.Call): String {
            val name = call.details.callerDisplayName?.toString()?.trim().orEmpty()
            if (name.isNotBlank()) return name.take(40)
            val number = call.details.handle?.schemeSpecificPart?.trim().orEmpty()
            if (number.isNotBlank()) return number.take(40)
            return "Desconocido"
        }
    }
}

class CintaInCallService : android.telecom.InCallService() {
    override fun onCreate() {
        super.onCreate()
        current = this
    }

    override fun onDestroy() {
        if (current === this) current = null
        super.onDestroy()
    }

    override fun onCallAdded(call: android.telecom.Call) {
        super.onCallAdded(call)
        CallHub.add(call)
        ServiceStarter.sync(this)
        showCall(call)
    }

    override fun onCallRemoved(call: android.telecom.Call) {
        CallHub.remove(call)
        if (CallHub.snapshot().isEmpty()) {
            getSystemService(NotificationManager::class.java).cancel(Notifier.CALL_ID)
        }
        super.onCallRemoved(call)
    }

    private fun showCall(call: android.telecom.Call) {
        Notifier.ensure(this)
        val open = PendingIntent.getActivity(
            this,
            5,
            Intent(this, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, Notifier.CALL_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Llamada")
            .setContentText(AnsweringService.callerLabel(call))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setFullScreenIntent(open, true)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
        getSystemService(NotificationManager::class.java).notify(Notifier.CALL_ID, notification)
        try {
            startActivity(Intent(this, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        } catch (_: Exception) {
        }
    }

    private fun applyRoute(speaker: Boolean) {
        @Suppress("DEPRECATION")
        try {
            setAudioRoute(
                if (speaker) android.telecom.CallAudioState.ROUTE_SPEAKER
                else android.telecom.CallAudioState.ROUTE_EARPIECE,
            )
        } catch (_: Exception) {
        }
    }

    companion object {
        @Volatile var current: CintaInCallService? = null

        fun earpiece() {
            current?.applyRoute(false)
        }

        fun speaker(on: Boolean) {
            current?.applyRoute(on)
        }

        fun mute(on: Boolean) {
            try {
                current?.setMuted(on)
            } catch (_: Exception) {
            }
        }
    }
}

class BootReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: android.content.Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        ServiceStarter.sync(context)
    }
}
