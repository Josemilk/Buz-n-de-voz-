package com.josemilk.cinta.audio

import android.content.Context
import android.media.AudioManager
import android.os.PowerManager
import com.josemilk.cinta.data.Vault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object Machine {
    suspend fun run(
        context: Context,
        caller: String,
        voiceCall: Boolean,
        shouldStop: () -> Boolean,
        onPhase: (String) -> Unit,
        onPrepare: () -> Unit = {},
        beforeRecord: suspend () -> Unit = {},
    ): Boolean = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val manager = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val previous = manager.mode
        val lock = (app.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cinta:machine")
        lock.setReferenceCounted(false)
        lock.acquire(4 * 60 * 1000L)
        try {
            withContext(Dispatchers.Main) { onPrepare() }
            if (voiceCall) manager.mode = AudioManager.MODE_IN_COMMUNICATION
            val vault = Vault.get(app)
            val settings = vault.snapshot().settings
            val clip = vault.activeClip()
            onPhase("Saludo")
            val greeting = if (clip != null) Dsp.apply(clip.pcm, clip.filters) else Synth.greeting()
            AudioIo.play(app, greeting, voiceCall, shouldStop)
            if (shouldStop()) return@withContext false
            if (settings.beep) {
                onPhase("Tono")
                AudioIo.play(app, Synth.beep(), voiceCall, shouldStop)
            }
            if (shouldStop()) return@withContext false
            beforeRecord()
            onPhase("Grabando")
            val recorded = AudioIo.record(
                context = app,
                maxSeconds = settings.maxSeconds,
                voiceCall = voiceCall,
                stopOnSilence = true,
                onLevel = {},
                shouldStop = shouldStop,
            )
            val usable = Dsp.trimSilence(recorded)
            if (usable.size < SR / 2) return@withContext false
            vault.addMessage(caller.ifBlank { "Desconocido" }, usable)
            true
        } finally {
            if (voiceCall) manager.mode = previous
            if (lock.isHeld) lock.release()
        }
    }
}
