package com.josemilk.cinta.ui

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.josemilk.cinta.audio.AudioIo
import com.josemilk.cinta.audio.Decoder
import com.josemilk.cinta.audio.Dsp
import com.josemilk.cinta.audio.Machine
import com.josemilk.cinta.audio.SR
import com.josemilk.cinta.audio.Speaker
import com.josemilk.cinta.audio.Synth
import com.josemilk.cinta.call.MachineBus
import com.josemilk.cinta.call.Notifier
import com.josemilk.cinta.call.ServiceStarter
import com.josemilk.cinta.data.Filters
import com.josemilk.cinta.data.MachineSettings
import com.josemilk.cinta.data.Vault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

class CintaViewModel(app: Application) : AndroidViewModel(app) {
    private val vault = Vault.get(app)
    val state = vault.state
    val playingId = MutableStateFlow<String?>(null)
    val recording = MutableStateFlow(false)
    val recordSeconds = MutableStateFlow(0)
    val level = MutableStateFlow(0f)
    val banner = MutableStateFlow<String?>(null)
    val simCaller = MutableStateFlow<String?>(null)
    val simPhase = MutableStateFlow("")

    private var playJob: Job? = null
    private var recordJob: Job? = null
    private var simJob: Job? = null
    private val stopRecord = AtomicBoolean(false)
    private val stopSim = AtomicBoolean(false)
    private val skipRings = AtomicBoolean(false)

    fun dismissBanner() {
        banner.value = null
    }

    fun updateSettings(block: (MachineSettings) -> MachineSettings) {
        vault.updateSettings(block)
        ServiceStarter.sync(getApplication())
    }

    fun selectGreeting(id: String) = vault.selectGreeting(id)

    fun deleteGreeting(id: String) {
        stopPlayback()
        vault.deleteGreeting(id)
    }

    fun updateFilters(id: String, filters: Filters) = vault.updateFilters(id, filters)

    fun fixVoice(id: String) {
        viewModelScope.launch {
            val greeting = state.value.greetings.find { it.id == id } ?: return@launch
            val pcm = withContext(Dispatchers.IO) { vault.readPcm(greeting.fileName) }
            if (pcm.isEmpty()) {
                banner.value = "No encontré el audio de ese saludo."
                return@launch
            }
            val report = Dsp.diagnose(pcm)
            vault.updateFilters(id, report.filters)
            banner.value = report.notes.joinToString(" ")
        }
    }

    fun playGreeting(id: String) {
        val greeting = state.value.greetings.find { it.id == id } ?: return
        if (playingId.value == id) {
            stopPlayback()
            return
        }
        stopPlayback()
        playJob = viewModelScope.launch {
            playingId.value = id
            val pcm = withContext(Dispatchers.IO) {
                val raw = vault.readPcm(greeting.fileName)
                if (raw.isEmpty()) ShortArray(0) else Dsp.apply(raw, greeting.filters)
            }
            if (pcm.isEmpty()) {
                banner.value = "Ese saludo ya no está en el teléfono."
                playingId.value = null
                return@launch
            }
            withContext(Dispatchers.IO) {
                AudioIo.play(getApplication(), pcm, false) { !isActive }
            }
            if (playingId.value == id) playingId.value = null
        }
    }

    fun playMessage(id: String) {
        val message = state.value.messages.find { it.id == id } ?: return
        if (playingId.value == id) {
            stopPlayback()
            return
        }
        stopPlayback()
        vault.markListened(id)
        playJob = viewModelScope.launch {
            playingId.value = id
            val pcm = withContext(Dispatchers.IO) { vault.readPcm(message.fileName) }
            if (pcm.isEmpty()) {
                banner.value = "No pude abrir ese mensaje."
                playingId.value = null
                return@launch
            }
            withContext(Dispatchers.IO) {
                AudioIo.play(getApplication(), pcm, false) { !isActive }
            }
            if (playingId.value == id) playingId.value = null
        }
    }

    fun deleteMessage(id: String) {
        if (playingId.value == id) stopPlayback()
        vault.deleteMessage(id)
    }

    fun clearMessages() {
        stopPlayback()
        vault.clearMessages()
    }

    fun toggleRecord() {
        if (recording.value) {
            stopRecord.set(true)
            return
        }
        stopPlayback()
        stopRecord.set(false)
        recordJob = viewModelScope.launch {
            recording.value = true
            recordSeconds.value = 0
            val started = SystemClock.elapsedRealtime()
            val ticker = launch {
                while (isActive && recording.value) {
                    recordSeconds.value = ((SystemClock.elapsedRealtime() - started) / 1000).toInt()
                    delay(200)
                }
            }
            val pcm = withContext(Dispatchers.IO) {
                AudioIo.record(
                    context = getApplication(),
                    maxSeconds = 45,
                    voiceCall = false,
                    stopOnSilence = false,
                    onLevel = { level.value = it },
                    shouldStop = { stopRecord.get() },
                )
            }
            ticker.cancel()
            recording.value = false
            level.value = 0f
            if (pcm.size < SR / 3) {
                banner.value = "La grabación quedó muy corta."
                return@launch
            }
            val count = state.value.greetings.count { it.source == "mic" } + 1
            vault.addGreeting("Saludo $count", pcm, "mic")
            banner.value = "Saludo guardado en el teléfono."
        }
    }

    fun createSynth() {
        viewModelScope.launch(Dispatchers.IO) {
            vault.addGreeting("Voz de prueba", Synth.greeting(), "synth", Filters())
            withContext(Dispatchers.Main) { banner.value = "Lista la voz de prueba, sin internet." }
        }
    }

    fun createSpoken(text: String) {
        viewModelScope.launch {
            banner.value = "Generando la voz en el teléfono…"
            val pcm = Speaker.speak(getApplication(), text)
            if (pcm == null) {
                vault.addGreeting("Voz de prueba", Synth.greeting(), "synth", Filters())
                banner.value = "Este teléfono no tiene voz en español. Guardé la voz de prueba."
                return@launch
            }
            vault.addGreeting("Saludo hablado", pcm, "synth")
            banner.value = "Saludo hablado guardado. Puedes arreglarlo con los filtros."
        }
    }

    fun importGreeting(uri: Uri) {
        viewModelScope.launch {
            banner.value = "Leyendo el audio…"
            val pcm = withContext(Dispatchers.IO) { runCatching { Decoder.decode(getApplication(), uri) }.getOrNull() }
            if (pcm == null || pcm.size < SR / 3) {
                banner.value = "No pude leer ese audio. Prueba WAV, MP3 o M4A guardados en el teléfono."
                return@launch
            }
            vault.addGreeting("Importado", pcm, "file")
            banner.value = "Saludo importado. Ya puedes afinarlo."
        }
    }

    fun simulate(caller: String) {
        if (MachineBus.running.value) {
            banner.value = "Hay una llamada real en curso."
            return
        }
        simJob?.cancel()
        stopSim.set(false)
        skipRings.set(false)
        simCaller.value = caller
        simJob = viewModelScope.launch {
            val rings = state.value.settings.rings.coerceIn(1, 8)
            var index = 1
            while (index <= rings && isActive && !stopSim.get() && !skipRings.get()) {
                simPhase.value = "Timbre $index de $rings"
                withContext(Dispatchers.IO) {
                    AudioIo.play(getApplication(), Synth.ring(), false) { stopSim.get() || skipRings.get() || !isActive }
                }
                var waited = 0
                while (waited < 900 && isActive && !stopSim.get() && !skipRings.get()) {
                    delay(50)
                    waited += 50
                }
                index++
            }
            if (!isActive || stopSim.get()) {
                simCaller.value = null
                simPhase.value = ""
                return@launch
            }
            val saved = Machine.run(
                context = getApplication(),
                caller = caller,
                voiceCall = false,
                shouldStop = { stopSim.get() || !isActive },
                onPhase = { simPhase.value = it },
            )
            if (saved) Notifier.message(getApplication(), caller)
            simCaller.value = null
            simPhase.value = ""
            if (saved) banner.value = "Mensaje guardado en el buzón."
        }
    }

    fun answerSimNow() {
        skipRings.set(true)
    }

    fun hangupSim() {
        stopSim.set(true)
        simJob?.cancel()
        simCaller.value = null
        simPhase.value = ""
    }

    private fun stopPlayback() {
        playJob?.cancel()
        playJob = null
        playingId.value = null
    }

    val levelFlow: StateFlow<Float> get() = level
}
