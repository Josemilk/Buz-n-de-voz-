package com.josemilk.cinta.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import com.josemilk.cinta.data.Filters
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

const val SR = 16000

class Pcm(val samples: ShortArray, val rate: Int)

class VoiceReport(val notes: List<String>, val filters: Filters)

object Wav {
    fun write(samples: ShortArray, sampleRate: Int): ByteArray {
        val dataSize = samples.size * 2
        val out = ByteArray(44 + dataSize)
        fun str(pos: Int, text: String) {
            text.forEachIndexed { index, char -> out[pos + index] = char.code.toByte() }
        }
        fun i16(pos: Int, value: Int) {
            out[pos] = (value and 0xff).toByte()
            out[pos + 1] = ((value shr 8) and 0xff).toByte()
        }
        fun i32(pos: Int, value: Int) {
            i16(pos, value)
            i16(pos + 2, value shr 16)
        }
        str(0, "RIFF")
        i32(4, 36 + dataSize)
        str(8, "WAVE")
        str(12, "fmt ")
        i32(16, 16)
        i16(20, 1)
        i16(22, 1)
        i32(24, sampleRate)
        i32(28, sampleRate * 2)
        i16(32, 2)
        i16(34, 16)
        str(36, "data")
        i32(40, dataSize)
        var cursor = 44
        for (sample in samples) {
            val value = sample.toInt()
            out[cursor++] = (value and 0xff).toByte()
            out[cursor++] = ((value shr 8) and 0xff).toByte()
        }
        return out
    }

    fun read(bytes: ByteArray): Pcm? {
        if (bytes.size < 44) return null
        if (bytes.decodeAscii(0, 4) != "RIFF" || bytes.decodeAscii(8, 4) != "WAVE") return null
        var pos = 12
        var channels = 1
        var rate = SR
        var bits = 16
        var format = 1
        var data: ByteArray? = null
        while (pos + 8 <= bytes.size) {
            val id = bytes.decodeAscii(pos, 4)
            val size = le32(bytes, pos + 4)
            val start = pos + 8
            if (size < 0 || start > bytes.size || size > bytes.size - start) break
            when (id) {
                "fmt " -> if (size >= 16) {
                    format = le16(bytes, start)
                    channels = le16(bytes, start + 2).coerceAtLeast(1)
                    rate = le32(bytes, start + 4).coerceAtLeast(8000)
                    bits = le16(bytes, start + 14)
                }
                "data" -> data = bytes.copyOfRange(start, start + size)
            }
            pos = start + size + (size and 1)
        }
        val raw = data ?: return null
        if (format != 1 || (bits != 16 && bits != 8)) return null
        val frame = if (bits == 16) 2 else 1
        val frames = raw.size / (frame * channels)
        val samples = ShortArray(frames)
        var index = 0
        for (frameIndex in 0 until frames) {
            var sum = 0
            for (channel in 0 until channels) {
                val at = (frameIndex * channels + channel) * frame
                sum += if (bits == 16) {
                    val lo = raw[at].toInt() and 0xff
                    val hi = raw[at + 1].toInt()
                    (hi shl 8) or lo
                } else {
                    ((raw[at].toInt() and 0xff) - 128) shl 8
                }
            }
            samples[index++] = (sum / channels).toShort()
        }
        return Pcm(samples, rate)
    }

    private fun ByteArray.decodeAscii(start: Int, length: Int): String {
        val chars = CharArray(length)
        for (i in 0 until length) chars[i] = (this[start + i].toInt() and 0xff).toChar()
        return String(chars)
    }

    private fun le16(bytes: ByteArray, index: Int): Int =
        (bytes[index].toInt() and 0xff) or ((bytes[index + 1].toInt() and 0xff) shl 8)

    private fun le32(bytes: ByteArray, index: Int): Int = le16(bytes, index) or (le16(bytes, index + 2) shl 16)
}

object Dsp {
    fun peaks(pcm: ShortArray, bars: Int = 32): List<Float> {
        if (pcm.isEmpty()) return List(bars) { 0.08f }
        val block = (pcm.size / bars).coerceAtLeast(1)
        var max = 1
        val raw = IntArray(bars)
        for (i in 0 until bars) {
            var peak = 0
            val start = i * block
            var j = 0
            while (j < block && start + j < pcm.size) {
                val mag = abs(pcm[start + j].toInt())
                if (mag > peak) peak = mag
                j += 2
            }
            raw[i] = peak
            if (peak > max) max = peak
        }
        return raw.map { (it.toFloat() / max).coerceIn(0.05f, 1f) }
    }

    fun trimSilence(pcm: ShortArray, threshold: Int = 450): ShortArray {
        if (pcm.size < SR / 5) return pcm
        var start = 0
        var end = pcm.lastIndex
        while (start < end && abs(pcm[start].toInt()) < threshold) start++
        while (end > start && abs(pcm[end].toInt()) < threshold) end--
        val pad = SR / 25
        val from = (start - pad).coerceAtLeast(0)
        val to = (end + pad).coerceAtMost(pcm.lastIndex)
        if (to - from < SR / 5) return pcm
        return pcm.copyOfRange(from, to + 1)
    }

    fun resample(input: ShortArray, from: Int, to: Int): ShortArray {
        if (from == to || input.isEmpty()) return input
        val outLen = (input.size.toLong() * to / from).toInt().coerceAtLeast(1)
        val out = ShortArray(outLen)
        val last = input.lastIndex
        for (i in 0 until outLen) {
            val src = i * (from.toDouble() / to)
            val left = src.toInt().coerceIn(0, last)
            val right = (left + 1).coerceAtMost(last)
            val frac = (src - left).toFloat()
            val mixed = input[left] * (1f - frac) + input[right] * frac
            out[i] = mixed.toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    fun apply(pcm: ShortArray, filters: Filters): ShortArray {
        if (pcm.isEmpty() || filters.isFlat()) return pcm.copyOf()
        val samples = FloatArray(pcm.size) { pcm[it] / 32768f }
        val sr = SR.toDouble()
        val highpass = biquadHighpass(48.0 + filters.clean * 1.5, 0.7, sr)
        val lowshelf = biquadLowShelf(240.0, (filters.warmth - 18) * 0.09, sr)
        val presence = biquadPeaking(2700.0, 0.9, (filters.clarity / 100.0) * 7.0, sr)
        val deess = biquadPeaking(6500.0, 1.3, -(filters.deess / 100.0) * 9.0, sr)
        val air = biquadHighShelf(7800.0, ((filters.air - 12) / 100.0) * 7.0, sr)
        for (i in samples.indices) {
            var y = samples[i]
            y = highpass.process(y)
            y = lowshelf.process(y)
            y = presence.process(y)
            y = deess.process(y)
            y = air.process(y)
            samples[i] = y
        }
        compress(samples, filters.compress / 100f)
        if (filters.room > 2) room(samples, filters.room / 100f)
        normalize(samples)
        return samples.toShortPcm()
    }

    fun diagnose(pcm: ShortArray): VoiceReport {
        if (pcm.size < SR / 4) {
            return VoiceReport(listOf("La toma es muy corta para medirla."), Filters())
        }
        val sr = SR.toDouble()
        var sum = 0.0
        var peak = 0.0
        var crossings = 0
        val lowCoef = exp(-2 * PI * 170 / sr)
        val sibCoef = exp(-2 * PI * 4800 / sr)
        var low = 0.0
        var sib = 0.0
        var lowEnergy = 0.0
        var sibEnergy = 0.0
        var prev = 0.0
        for (i in pcm.indices) {
            val sample = pcm[i] / 32768.0
            sum += sample * sample
            val mag = abs(sample)
            if (mag > peak) peak = mag
            if (i > 0 && (sample >= 0) != (prev >= 0)) crossings++
            prev = sample
            low = lowCoef * low + (1 - lowCoef) * sample
            lowEnergy += low * low
            sib = sibCoef * sib + (1 - sibCoef) * sample
            val hiss = sample - sib
            sibEnergy += hiss * hiss
        }
        val n = pcm.size.coerceAtLeast(1)
        val rms = sqrt(sum / n)
        val crest = peak / (rms + 1e-9)
        val lowRatio = lowEnergy / (sum + 1e-9)
        val sibRatio = sibEnergy / (sum + 1e-9)
        val zcr = crossings.toDouble() / n
        var clean = 48
        var warmth = 40
        var clarity = 46
        var air = 16
        var deess = 34
        var compress = 52
        val notes = mutableListOf<String>()
        if (lowRatio > 0.7) {
            clean = 80
            notes += "Hay rumor grave: subí la limpieza."
        } else if (lowRatio < 0.38) {
            clean = 24
            warmth = 68
            notes += "La voz está delgada: le dejé más cuerpo."
        }
        if (sibRatio > 0.11 || zcr > 0.11) {
            deess = 76
            air = 8
            notes += "Las eses cortan: apliqué de-ess."
        } else if (zcr < 0.028) {
            clarity = 72
            air = 34
            notes += "Suena opaca: abrí claridad y un poco de aire."
        }
        if (crest > 9) {
            compress = 84
            notes += "El volumen salta: emparejé el nivel."
        } else if (crest < 3.4 && rms > 0.02) {
            compress = 26
            notes += "Ya viene pareja: bajé la compresión para que no suene aplastada."
        }
        if (rms < 0.02 && peak > 0) notes += "Está bajita. Al escuchar el arreglo, el nivel se levanta solo."
        if (notes.isEmpty()) notes += "La toma está bastante limpia. El pase de estudio alcanza."
        return VoiceReport(notes, Filters(clean, warmth, clarity, air, deess, compress, 4))
    }

    private fun compress(samples: FloatArray, amount: Float) {
        if (amount <= 0.001f) return
        val threshold = 10.0.pow(((-8.0 - amount * 20.0) / 20.0)).toFloat()
        val ratio = 1f + amount * 5f
        val makeup = 1f + amount * 1.15f
        val attack = exp(-1.0 / (SR * 0.008)).toFloat()
        val release = exp(-1.0 / (SR * 0.2)).toFloat()
        var env = 0f
        for (i in samples.indices) {
            val sample = samples[i]
            val mag = abs(sample)
            val coef = if (mag > env) attack else release
            env = coef * env + (1f - coef) * mag
            val gain = if (env > threshold && env > 1e-6f) {
                val over = env / threshold
                val desired = threshold * over.toDouble().pow((1.0 / ratio).toDouble()).toFloat()
                desired / env
            } else {
                1f
            }
            samples[i] = sample * gain * makeup
        }
    }

    private fun room(samples: FloatArray, amount: Float) {
        val delay = ((0.042f + amount * 0.03f) * SR).toInt().coerceIn(1, SR)
        val line = FloatArray(delay)
        var index = 0
        val feedback = amount * 0.26f
        val wet = amount * 0.28f
        for (i in samples.indices) {
            val dry = samples[i]
            val delayed = line[index]
            line[index] = dry + delayed * feedback
            index++
            if (index == delay) index = 0
            samples[i] = dry + delayed * wet
        }
    }

    private fun normalize(samples: FloatArray) {
        var peak = 0f
        for (sample in samples) {
            val mag = abs(sample)
            if (mag > peak) peak = mag
        }
        if (peak < 1e-4f) return
        val gain = (0.89f / peak).coerceAtMost(4f)
        if (abs(gain - 1f) < 0.04f) return
        for (i in samples.indices) samples[i] *= gain
    }

    private fun FloatArray.toShortPcm(): ShortArray {
        val out = ShortArray(size)
        for (i in indices) out[i] = (this[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
        return out
    }
}

private class Biquad(
    b0: Double,
    b1: Double,
    b2: Double,
    a0: Double,
    a1: Double,
    a2: Double,
) {
    private val nb0 = (b0 / a0).toFloat()
    private val nb1 = (b1 / a0).toFloat()
    private val nb2 = (b2 / a0).toFloat()
    private val na1 = (a1 / a0).toFloat()
    private val na2 = (a2 / a0).toFloat()
    private var z1 = 0f
    private var z2 = 0f

    fun process(x: Float): Float {
        val y = nb0 * x + z1
        z1 = nb1 * x - na1 * y + z2
        z2 = nb2 * x - na2 * y
        return y
    }
}

private fun biquadHighpass(freq: Double, q: Double, sr: Double): Biquad {
    val w0 = 2 * PI * freq / sr
    val alpha = sin(w0) / (2 * q)
    val cosw = cos(w0)
    return Biquad((1 + cosw) / 2, -(1 + cosw), (1 + cosw) / 2, 1 + alpha, -2 * cosw, 1 - alpha)
}

private fun biquadPeaking(freq: Double, q: Double, gainDb: Double, sr: Double): Biquad {
    val a = 10.0.pow(gainDb / 40.0)
    val w0 = 2 * PI * freq / sr
    val alpha = sin(w0) / (2 * q)
    val cosw = cos(w0)
    return Biquad(1 + alpha * a, -2 * cosw, 1 - alpha * a, 1 + alpha / a, -2 * cosw, 1 - alpha / a)
}

private fun biquadLowShelf(freq: Double, gainDb: Double, sr: Double): Biquad {
    val a = 10.0.pow(gainDb / 40.0)
    val w0 = 2 * PI * freq / sr
    val cosw = cos(w0)
    val alpha = sin(w0) / 2 * sqrt((a + 1 / a) * (1 / 1.0 - 1) + 2)
    val twoSqrtAAlpha = 2 * sqrt(a) * alpha
    return Biquad(
        a * ((a + 1) - (a - 1) * cosw + twoSqrtAAlpha),
        2 * a * ((a - 1) - (a + 1) * cosw),
        a * ((a + 1) - (a - 1) * cosw - twoSqrtAAlpha),
        (a + 1) + (a - 1) * cosw + twoSqrtAAlpha,
        -2 * ((a - 1) + (a + 1) * cosw),
        (a + 1) + (a - 1) * cosw - twoSqrtAAlpha,
    )
}

private fun biquadHighShelf(freq: Double, gainDb: Double, sr: Double): Biquad {
    val a = 10.0.pow(gainDb / 40.0)
    val w0 = 2 * PI * freq / sr
    val cosw = cos(w0)
    val alpha = sin(w0) / 2 * sqrt((a + 1 / a) * (1 / 1.0 - 1) + 2)
    val twoSqrtAAlpha = 2 * sqrt(a) * alpha
    return Biquad(
        a * ((a + 1) + (a - 1) * cosw + twoSqrtAAlpha),
        -2 * a * ((a - 1) + (a + 1) * cosw),
        a * ((a + 1) + (a - 1) * cosw - twoSqrtAAlpha),
        (a + 1) - (a - 1) * cosw + twoSqrtAAlpha,
        2 * ((a - 1) - (a + 1) * cosw),
        (a + 1) - (a - 1) * cosw - twoSqrtAAlpha,
    )
}

object Synth {
    fun greeting(): ShortArray = render(GREETING, 172.0)
    fun beep(): ShortArray = tone(880.0, 0.36, 0.22f)
    fun ring(): ShortArray {
        val n = (SR * 0.7f).toInt()
        val out = ShortArray(n)
        for (i in 0 until n) {
            val t = i / SR.toFloat()
            val env = when {
                t < 0.03f -> t / 0.03f
                t > 0.62f -> ((0.7f - t) / 0.08f).coerceIn(0f, 1f)
                else -> 1f
            }
            val sample = (sin(2 * PI * 440 * i / SR) + sin(2 * PI * 480 * i / SR)) * 0.08 * env
            out[i] = (sample * 32767f).toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    private fun tone(freq: Double, seconds: Double, gain: Float): ShortArray {
        val n = (SR * seconds).toInt()
        val out = ShortArray(n)
        for (i in 0 until n) {
            val t = i / SR.toFloat()
            val attack = (t / 0.015f).coerceAtMost(1f)
            val release = ((seconds.toFloat() - t) / 0.12f).coerceIn(0f, 1f)
            val sample = sin(2 * PI * freq * i / SR).toFloat() * gain * attack * release
            out[i] = (sample * 32767f).toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    private fun render(phones: List<Phone>, basePitch: Double): ShortArray {
        val sr = SR
        val total = phones.sumOf { it.d } + 0.18
        val length = (sr * total).toInt()
        val data = FloatArray(length)
        val random = Random(7)
        var cursor = 0.05
        for (phone in phones) {
            val start = (cursor * sr).toInt()
            val end = minOf(length, ((cursor + phone.d) * sr).toInt())
            cursor += phone.d
            if (phone is Phone.Z || end <= start) continue
            val vowel = if (phone is Phone.S) 'a' else phone.vowel
            val pitchMul = when (phone) {
                is Phone.S -> 1.0
                is Phone.M -> phone.f
                is Phone.V -> phone.f
                is Phone.Z -> 1.0
            }
            val pitch = basePitch * pitchMul
            val formants = FORMANTS.getValue(vowel)
            val nasal = phone is Phone.M
            val coefs = arrayOf(
                bandpass(if (nasal) 280.0 else formants[0], if (nasal) 5.0 else 7.0, sr.toDouble()),
                bandpass(if (nasal) formants[1] * 0.72 else formants[1], 9.0, sr.toDouble()),
                bandpass(formants[2], 8.0, sr.toDouble()),
            )
            val states = Array(3) { BiState() }
            val weights = if (nasal) doubleArrayOf(0.85, 0.22, 0.05) else doubleArrayOf(0.62, 0.34, 0.16)
            var phase = 0.0
            var hiss = 0.0
            for (i in start until end) {
                val u = (i - start).toDouble() / (end - start)
                val att = minOf(1.0, (i - start) / (0.018 * sr))
                val rel = minOf(1.0, (end - i) / (0.028 * sr))
                val env = att * rel * (1 - u * 0.08)
                val source = if (phone is Phone.S) {
                    val noise = random.nextDouble() * 2 - 1
                    hiss = hiss * 0.55 + noise * 0.45
                    (noise - hiss) * 0.55
                } else {
                    val wobble = 1 + 0.012 * sin(2 * PI * 4.6 * i / sr)
                    phase += pitch * wobble / sr
                    if (phase >= 1) phase -= 1
                    val open = 0.4
                    val glottal = if (phase < open) {
                        sin(PI * phase / open).pow(1.15)
                    } else {
                        -0.22 * sin(PI * (phase - open) / (1 - open))
                    }
                    glottal + (random.nextDouble() * 2 - 1) * 0.035
                }
                var y = 0.0
                for (b in 0 until 3) y += step(coefs[b], source, states[b]) * weights[b]
                data[i] = (y * env * if (phone is Phone.S) 0.45 else 0.9).toFloat()
            }
        }
        var lp = 0.0
        var peak = 0.001
        val cut = exp(-2 * PI * 3600 / sr)
        for (i in data.indices) {
            lp = cut * lp + (1 - cut) * data[i]
            data[i] = lp.toFloat()
            val mag = abs(lp)
            if (mag > peak) peak = mag
        }
        val gain = minOf(3.5, 0.82 / peak)
        val out = ShortArray(data.size)
        for (i in data.indices) {
            out[i] = (data[i] * gain * 32767.0).toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    private fun bandpass(freq: Double, q: Double, sr: Double): DoubleArray {
        val w0 = 2 * PI * freq / sr
        val alpha = sin(w0) / (2 * q)
        val a0 = 1 + alpha
        return doubleArrayOf(
            alpha / a0,
            0.0,
            -alpha / a0,
            -2 * cos(w0) / a0,
            (1 - alpha) / a0,
        )
    }

    private fun step(c: DoubleArray, x: Double, s: BiState): Double {
        val y = c[0] * x + s.z1
        s.z1 = c[1] * x - c[3] * y + s.z2
        s.z2 = c[2] * x - c[4] * y
        return y
    }

    private class BiState {
        var z1 = 0.0
        var z2 = 0.0
    }

    private sealed class Phone(val d: Double, val vowel: Char) {
        class V(v: Char, d: Double, val f: Double = 1.0) : Phone(d, v)
        class M(v: Char, d: Double, val f: Double = 0.94) : Phone(d, v)
        class S(d: Double) : Phone(d, 'a')
        class Z(d: Double) : Phone(d, 'a')
    }

    private val FORMANTS = mapOf(
        'a' to doubleArrayOf(740.0, 1180.0, 2550.0),
        'e' to doubleArrayOf(430.0, 1960.0, 2640.0),
        'i' to doubleArrayOf(310.0, 2280.0, 3000.0),
        'o' to doubleArrayOf(470.0, 860.0, 2480.0),
        'u' to doubleArrayOf(330.0, 740.0, 2300.0),
    )

    private val GREETING = listOf(
        Phone.V('o', 0.10), Phone.V('a', 0.22, 1.04), Phone.Z(0.16),
        Phone.M('o', 0.20), Phone.Z(0.09), Phone.V('u', 0.08), Phone.V('e', 0.12), Phone.V('o', 0.16),
        Phone.Z(0.10), Phone.V('a', 0.10), Phone.V('e', 0.12), Phone.V('e', 0.18), Phone.Z(0.08),
        Phone.V('a', 0.10), Phone.V('o', 0.10), Phone.V('a', 0.20), Phone.Z(0.20),
        Phone.V('e', 0.10), Phone.V('a', 0.20), Phone.Z(0.10), Phone.V('u', 0.16), Phone.Z(0.08),
        Phone.M('e', 0.12), Phone.S(0.07), Phone.V('a', 0.12), Phone.V('e', 0.16), Phone.Z(0.12),
        Phone.V('e', 0.09), Phone.S(0.07), Phone.V('u', 0.10), Phone.V('e', 0.16), Phone.Z(0.08),
        Phone.V('e', 0.12), Phone.Z(0.06), Phone.V('o', 0.12), Phone.M('o', 0.24, 0.92),
    )
}

object AudioIo {
    fun play(context: Context, pcm: ShortArray, voiceCall: Boolean, shouldStop: () -> Boolean) {
        if (pcm.isEmpty() || shouldStop()) return
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val attrs = AudioAttributes.Builder()
            .setUsage(if (voiceCall) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val min = AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            .coerceAtLeast(SR / 4)
        val focus = if (voiceCall) {
            null
        } else {
            android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attrs)
                .build()
                .also { manager.requestAudioFocus(it) }
        }
        val track = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SR)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(min * 2)
            .build()
        try {
            if (track.state != AudioTrack.STATE_INITIALIZED) return
            track.play()
            var offset = 0
            while (offset < pcm.size && !shouldStop()) {
                val count = minOf(2048, pcm.size - offset)
                val wrote = track.write(pcm, offset, count)
                if (wrote <= 0) break
                offset += wrote
            }
            val drain = SystemClock.elapsedRealtime() + 180
            while (SystemClock.elapsedRealtime() < drain && !shouldStop()) Thread.sleep(30)
            track.pause()
            track.flush()
        } finally {
            track.release()
            if (focus != null) manager.abandonAudioFocusRequest(focus)
        }
    }

    fun record(
        context: Context,
        maxSeconds: Int,
        voiceCall: Boolean,
        stopOnSilence: Boolean,
        onLevel: (Float) -> Unit,
        shouldStop: () -> Boolean,
    ): ShortArray {
        val min = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return ShortArray(0)
        val bufSize = min * 2
        val sources = if (voiceCall) {
            intArrayOf(
                android.media.MediaRecorder.AudioSource.VOICE_CALL,
                android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                android.media.MediaRecorder.AudioSource.MIC,
            )
        } else {
            intArrayOf(android.media.MediaRecorder.AudioSource.MIC)
        }
        val recorder = openFirstRecorder(sources, bufSize) ?: return ShortArray(0)
        val bytes = ByteArrayOutputStream()
        val buf = ShortArray(min)
        var silenceMs = 0
        var heard = false
        var lastLevel = 0L
        try {
            recorder.startRecording()
            val deadline = SystemClock.elapsedRealtime() + maxSeconds.coerceIn(5, 180) * 1000L
            while (SystemClock.elapsedRealtime() < deadline && !shouldStop()) {
                val count = recorder.read(buf, 0, buf.size)
                if (count <= 0) continue
                var energy = 0.0
                for (i in 0 until count) {
                    val sample = buf[i].toInt()
                    bytes.write(sample and 0xff)
                    bytes.write((sample shr 8) and 0xff)
                    energy += sample * sample
                }
                val rms = sqrt(energy / count) / 32768.0
                val now = SystemClock.elapsedRealtime()
                if (now - lastLevel > 80) {
                    lastLevel = now
                    onLevel(rms.toFloat().coerceIn(0f, 1f))
                }
                if (stopOnSilence) {
                    val sliceMs = count * 1000 / SR
                    if (rms > 0.02) {
                        heard = true
                        silenceMs = 0
                    } else if (heard) {
                        silenceMs += sliceMs
                        if (silenceMs > 6500) break
                    }
                }
            }
        } catch (_: Exception) {
            return ShortArray(0)
        } finally {
            try {
                recorder.stop()
            } catch (_: Exception) {
            }
            recorder.release()
            onLevel(0f)
        }
        return bytes.toShortArray()
    }

    private fun openFirstRecorder(sources: IntArray, bufSize: Int): AudioRecord? {
        for (source in sources) {
            val recorder = openRecorder(source, bufSize)
            if (recorder != null) return recorder
        }
        return null
    }

    private fun openRecorder(source: Int, bufSize: Int): AudioRecord? {
        return try {
            val recorder = AudioRecord(
                source,
                SR,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize,
            )
            if (recorder.state == AudioRecord.STATE_INITIALIZED) recorder else {
                recorder.release()
                null
            }
        } catch (_: SecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun ByteArrayOutputStream.toShortArray(): ShortArray {
        val raw = toByteArray()
        val out = ShortArray(raw.size / 2)
        var i = 0
        var j = 0
        while (i + 1 < raw.size) {
            val lo = raw[i].toInt() and 0xff
            val hi = raw[i + 1].toInt()
            out[j++] = ((hi shl 8) or lo).toShort()
            i += 2
        }
        return out
    }
}

object Decoder {
    fun decode(context: Context, uri: Uri): ShortArray {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val header = ByteArray(12)
            val read = input.read(header)
            if (read >= 12 && header.decodeToString(0, 4) == "RIFF") {
                val all = header + input.readBytes()
                val pcm = Wav.read(all) ?: return ShortArray(0)
                val clipped = limit(if (pcm.rate == SR) pcm.samples else Dsp.resample(pcm.samples, pcm.rate, SR))
                return clipped
            }
        }
        return limit(decodeCompressed(context, uri))
    }

    private fun limit(pcm: ShortArray): ShortArray {
        val max = SR * 60
        return if (pcm.size > max) pcm.copyOfRange(0, max) else pcm
    }

    private fun decodeCompressed(context: Context, uri: Uri): ShortArray {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)
        var track = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val candidate = extractor.getTrackFormat(i)
            val mime = candidate.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                track = i
                format = candidate
                break
            }
        }
        if (track < 0 || format == null) {
            extractor.release()
            return ShortArray(0)
        }
        extractor.selectTrack(track)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: return ShortArray(0)
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()
        val info = MediaCodec.BufferInfo()
        val raw = ByteArrayOutputStream()
        var rate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else SR
        var channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1
        var encoding = AudioFormat.ENCODING_PCM_16BIT
        var inputDone = false
        var outputDone = false
        try {
            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buffer = codec.getInputBuffer(inIndex) ?: continue
                        buffer.clear()
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                when (val outIndex = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFormat = codec.outputFormat
                        if (outFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) rate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        if (outFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (outFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = outFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    }
                    else -> if (outIndex >= 0) {
                        val buffer = codec.getOutputBuffer(outIndex)
                        if (buffer != null && info.size > 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val chunk = ByteArray(info.size)
                            buffer.get(chunk)
                            raw.write(chunk)
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
        } finally {
            codec.stop()
            codec.release()
            extractor.release()
        }
        val bytes = raw.toByteArray()
        val samples = when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> floatsToShorts(bytes, channels)
            AudioFormat.ENCODING_PCM_8BIT -> bytesToShorts8(bytes, channels)
            else -> bytesToShorts16(bytes, channels)
        }
        return if (rate == SR) samples else Dsp.resample(samples, rate, SR)
    }

    private fun bytesToShorts16(bytes: ByteArray, channels: Int): ShortArray {
        val ch = channels.coerceAtLeast(1)
        val frames = bytes.size / (2 * ch)
        val out = ShortArray(frames)
        for (frame in 0 until frames) {
            var sum = 0
            for (channel in 0 until ch) {
                val at = (frame * ch + channel) * 2
                val lo = bytes[at].toInt() and 0xff
                val hi = bytes[at + 1].toInt()
                sum += (hi shl 8) or lo
            }
            out[frame] = (sum / ch).toShort()
        }
        return out
    }

    private fun bytesToShorts8(bytes: ByteArray, channels: Int): ShortArray {
        val ch = channels.coerceAtLeast(1)
        val frames = bytes.size / ch
        val out = ShortArray(frames)
        for (frame in 0 until frames) {
            var sum = 0
            for (channel in 0 until ch) sum += ((bytes[frame * ch + channel].toInt() and 0xff) - 128) shl 8
            out[frame] = (sum / ch).toShort()
        }
        return out
    }

    private fun floatsToShorts(bytes: ByteArray, channels: Int): ShortArray {
        val ch = channels.coerceAtLeast(1)
        val frames = bytes.size / (4 * ch)
        val out = ShortArray(frames)
        val buffer = ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.nativeOrder())
        for (frame in 0 until frames) {
            var sum = 0f
            for (channel in 0 until ch) sum += buffer.float
            out[frame] = (sum / ch * 32767f).toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }
}
