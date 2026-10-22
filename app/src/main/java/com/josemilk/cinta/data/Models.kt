package com.josemilk.cinta.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class Filters(
    val clean: Int = 32,
    val warmth: Int = 36,
    val clarity: Int = 30,
    val air: Int = 14,
    val deess: Int = 26,
    val compress: Int = 38,
    val room: Int = 4,
) {
    fun with(key: String, value: Int): Filters {
        val v = value.coerceIn(0, 100)
        return when (key) {
            "clean" -> copy(clean = v)
            "warmth" -> copy(warmth = v)
            "clarity" -> copy(clarity = v)
            "air" -> copy(air = v)
            "deess" -> copy(deess = v)
            "compress" -> copy(compress = v)
            "room" -> copy(room = v)
            else -> this
        }
    }

    fun isFlat(): Boolean =
        clean == 0 && warmth == 0 && clarity == 0 && air == 0 && deess == 0 && compress == 0 && room == 0
}

data class FilterField(val key: String, val label: String, val hint: String)

val FILTER_FIELDS = listOf(
    FilterField("clean", "Limpieza", "Recorta rumor grave y murmullo."),
    FilterField("warmth", "Calidez", "Da cuerpo a la voz."),
    FilterField("clarity", "Claridad", "Hace las palabras más presentes."),
    FilterField("air", "Aire", "Brillo alto, con cuidado."),
    FilterField("deess", "De-ess", "Suaviza las eses."),
    FilterField("compress", "Nivel", "Empareja los saltos de volumen."),
    FilterField("room", "Sala", "Un poco de espacio, sin eco largo."),
)

data class Preset(val id: String, val name: String, val filters: Filters)

val PRESETS = listOf(
    Preset("estudio", "Estudio", Filters(55, 40, 58, 20, 48, 60, 4)),
    Preset("nitido", "Nítido", Filters(74, 16, 64, 26, 70, 50, 0)),
    Preset("calido", "Cálido", Filters(34, 80, 24, 6, 40, 42, 8)),
    Preset("radio", "Radio", Filters(48, 58, 52, 16, 36, 82, 12)),
    Preset("natural", "Natural", Filters()),
)

val DURATION_OPTIONS = listOf(
    30 to "30 s",
    60 to "1 min",
    120 to "2 min",
    180 to "3 min",
)

const val DEFAULT_SCRIPT = "Hola. No puedo atender ahora. Deja tu mensaje después del tono."

data class Greeting(
    val id: String,
    val name: String,
    val createdAt: Long,
    val durationMs: Int,
    val filters: Filters,
    val fileName: String,
    val source: String,
    val peaks: List<Float>,
)

data class Voicemail(
    val id: String,
    val caller: String,
    val createdAt: Long,
    val durationMs: Int,
    val listened: Boolean,
    val fileName: String,
    val peaks: List<Float>,
)

data class MachineSettings(
    val enabled: Boolean = true,
    val rings: Int = 4,
    val maxSeconds: Int = 60,
    val beep: Boolean = true,
    val ownerName: String = "",
    val watchCalls: Boolean = true,
    val script: String = DEFAULT_SCRIPT,
)

data class Persisted(
    val settings: MachineSettings = MachineSettings(),
    val greetings: List<Greeting> = emptyList(),
    val messages: List<Voicemail> = emptyList(),
    val activeGreetingId: String? = null,
)

fun formatClock(ms: Int): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

fun formatWhen(ts: Long): String {
    val zone = ZoneId.systemDefault()
    val dt = Instant.ofEpochMilli(ts).atZone(zone)
    val today = LocalDate.now(zone)
    val time = dt.format(DateTimeFormatter.ofPattern("HH:mm"))
    return when (dt.toLocalDate()) {
        today -> "Hoy · $time"
        today.minusDays(1) -> "Ayer · $time"
        else -> dt.format(DateTimeFormatter.ofPattern("d MMM · HH:mm", Locale("es")))
    }
}
