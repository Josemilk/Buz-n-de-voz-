package com.josemilk.cinta.audio

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.josemilk.cinta.data.DEFAULT_SCRIPT
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume

object Speaker {
    suspend fun speak(context: Context, text: String): ShortArray? {
        val script = text.trim().ifBlank { DEFAULT_SCRIPT }.take(240)
        val pcm = withTimeoutOrNull(12_000) {
            suspendCancellableCoroutine { cont ->
                var engine: TextToSpeech? = null
                engine = TextToSpeech(context.applicationContext) { status ->
                    val tts = engine
                    if (status != TextToSpeech.SUCCESS || tts == null) {
                        if (cont.isActive) cont.resume(null)
                        return@TextToSpeech
                    }
                    val locales = listOf(Locale("es", "MX"), Locale("es", "ES"), Locale("es", "US"), Locale("es"))
                    val locale = locales.firstOrNull { code ->
                        tts.isLanguageAvailable(code) >= TextToSpeech.LANG_AVAILABLE
                    }
                    if (locale == null) {
                        tts.shutdown()
                        if (cont.isActive) cont.resume(null)
                        return@TextToSpeech
                    }
                    tts.language = locale
                    val file = File(context.cacheDir, "cinta-tts.wav")
                    tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) = Unit
                        override fun onDone(utteranceId: String?) {
                            val decoded = runCatching {
                                val read = Wav.read(file.readBytes())
                                file.delete()
                                if (read == null) null
                                else if (read.rate == SR) read.samples else Dsp.resample(read.samples, read.rate, SR)
                            }.getOrNull()
                            tts.shutdown()
                            if (cont.isActive) cont.resume(decoded)
                        }
                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            file.delete()
                            tts.shutdown()
                            if (cont.isActive) cont.resume(null)
                        }
                    })
                    val ok = tts.synthesizeToFile(script, Bundle(), file, "cinta")
                    if (ok != TextToSpeech.SUCCESS) {
                        tts.shutdown()
                        if (cont.isActive) cont.resume(null)
                    }
                }
                cont.invokeOnCancellation { engine?.shutdown() }
            }
        }
        return pcm?.takeIf { it.size > SR / 3 }
    }
}
