package com.josemilk.cinta.data

import android.content.Context
import com.josemilk.cinta.audio.Dsp
import com.josemilk.cinta.audio.SR
import com.josemilk.cinta.audio.Synth
import com.josemilk.cinta.audio.Wav
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class Clip(val pcm: ShortArray, val filters: Filters)

class Vault private constructor(private val app: Context) {
    private val dir = File(app.filesDir, "cinta").apply { mkdirs() }
    private val audioDir = File(dir, "audio").apply { mkdirs() }
    private val stateFile = File(dir, "state.json")
    private val lock = Any()
    private val mutable = MutableStateFlow(Persisted())
    val state: StateFlow<Persisted> = mutable

    init {
        synchronized(lock) {
            val loaded = readFile()
            if (loaded.greetings.isEmpty()) {
                val pcm = Synth.greeting()
                val greeting = greetingFrom("Voz de prueba", pcm, "synth", Filters())
                writeWav(greeting.fileName, pcm)
                val next = loaded.copy(greetings = listOf(greeting), activeGreetingId = greeting.id)
                persist(next)
                mutable.value = next
            } else {
                mutable.value = loaded
            }
        }
    }

    fun snapshot(): Persisted = mutable.value

    fun updateSettings(block: (MachineSettings) -> MachineSettings) {
        update { current ->
            current.copy(settings = sanitize(block(current.settings)))
        }
    }

    fun addGreeting(name: String, pcm: ShortArray, source: String, filters: Filters = Filters()): Greeting {
        val trimmed = Dsp.trimSilence(pcm)
        val greeting = greetingFrom(name, trimmed, source, filters)
        writeWav(greeting.fileName, trimmed)
        update { current ->
            current.copy(
                greetings = listOf(greeting) + current.greetings,
                activeGreetingId = greeting.id,
            )
        }
        return greeting
    }

    fun selectGreeting(id: String) {
        update { current ->
            if (current.greetings.none { it.id == id }) current
            else current.copy(activeGreetingId = id)
        }
    }

    fun deleteGreeting(id: String) {
        update { current ->
            val target = current.greetings.find { it.id == id }
            if (target != null) audioFile(target.fileName).delete()
            val greetings = current.greetings.filterNot { it.id == id }
            val active = when {
                current.activeGreetingId != id -> current.activeGreetingId
                else -> greetings.firstOrNull()?.id
            }
            current.copy(greetings = greetings, activeGreetingId = active)
        }
    }

    fun updateFilters(id: String, filters: Filters) {
        update { current ->
            current.copy(
                greetings = current.greetings.map { item ->
                    if (item.id == id) item.copy(filters = filters) else item
                },
            )
        }
    }

    fun activeClip(): Clip? {
        val current = snapshot()
        val greeting = current.greetings.firstOrNull { it.id == current.activeGreetingId }
            ?: current.greetings.firstOrNull()
            ?: return null
        val pcm = readPcm(greeting.fileName)
        if (pcm.isEmpty()) return null
        return Clip(pcm, greeting.filters)
    }

    fun readPcm(fileName: String): ShortArray {
        val file = audioFile(fileName)
        if (!file.exists()) return ShortArray(0)
        val pcm = Wav.read(file.readBytes()) ?: return ShortArray(0)
        return if (pcm.rate == SR) pcm.samples else Dsp.resample(pcm.samples, pcm.rate, SR)
    }

    fun addMessage(caller: String, pcm: ShortArray): Voicemail {
        val trimmed = if (pcm.size > SR * 180) pcm.copyOfRange(0, SR * 180) else pcm
        val item = Voicemail(
            id = UUID.randomUUID().toString(),
            caller = caller.ifBlank { "Desconocido" }.take(40),
            createdAt = System.currentTimeMillis(),
            durationMs = trimmed.size * 1000 / SR,
            listened = false,
            fileName = "m_${System.currentTimeMillis()}.wav",
            peaks = Dsp.peaks(trimmed),
        )
        writeWav(item.fileName, trimmed)
        update { current -> current.copy(messages = listOf(item) + current.messages) }
        return item
    }

    fun markListened(id: String) {
        update { current ->
            current.copy(
                messages = current.messages.map { item ->
                    if (item.id == id) item.copy(listened = true) else item
                },
            )
        }
    }

    fun deleteMessage(id: String) {
        update { current ->
            current.messages.find { it.id == id }?.let { audioFile(it.fileName).delete() }
            current.copy(messages = current.messages.filterNot { it.id == id })
        }
    }

    fun clearMessages() {
        update { current ->
            current.messages.forEach { audioFile(it.fileName).delete() }
            current.copy(messages = emptyList())
        }
    }

    private fun greetingFrom(name: String, pcm: ShortArray, source: String, filters: Filters) = Greeting(
        id = UUID.randomUUID().toString(),
        name = name.ifBlank { "Saludo" }.take(40),
        createdAt = System.currentTimeMillis(),
        durationMs = pcm.size * 1000 / SR,
        filters = filters,
        fileName = "g_${System.currentTimeMillis()}.wav",
        source = source,
        peaks = Dsp.peaks(pcm),
    )

    private fun update(block: (Persisted) -> Persisted) {
        synchronized(lock) {
            val next = block(mutable.value)
            persist(next)
            mutable.value = next
        }
    }

    private fun writeWav(fileName: String, pcm: ShortArray) {
        val safe = safeName(fileName)
        if (safe.isEmpty()) return
        File(audioDir, safe).writeBytes(Wav.write(pcm, SR))
    }

    private fun audioFile(fileName: String): File = File(audioDir, safeName(fileName).ifEmpty { "invalid.wav" })

    private fun persist(value: Persisted) {
        val tmp = File(dir, "state.json.tmp")
        tmp.writeText(toJson(value).toString())
        if (!tmp.renameTo(stateFile)) {
            stateFile.writeText(tmp.readText())
            tmp.delete()
        }
    }

    private fun readFile(): Persisted {
        if (!stateFile.exists()) return Persisted()
        return try {
            parse(JSONObject(stateFile.readText()))
        } catch (_: Exception) {
            Persisted()
        }
    }

    companion object {
        @Volatile private var instance: Vault? = null

        fun get(context: Context): Vault {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: Vault(context.applicationContext).also { instance = it }
            }
        }

        private fun safeName(name: String): String {
            val base = name.substringAfterLast('/').substringAfterLast('\\')
            return if (base.matches(Regex("[A-Za-z0-9_.-]+\\.wav"))) base else ""
        }

        private fun sanitize(settings: MachineSettings): MachineSettings {
            val seconds = if (DURATION_OPTIONS.any { it.first == settings.maxSeconds }) settings.maxSeconds else 60
            return settings.copy(
                rings = settings.rings.coerceIn(1, 8),
                maxSeconds = seconds,
                ownerName = settings.ownerName.take(32),
                script = settings.script.trim().take(240).ifBlank { DEFAULT_SCRIPT },
            )
        }

        private fun toJson(value: Persisted): JSONObject {
            val root = JSONObject()
            root.put("activeGreetingId", value.activeGreetingId ?: JSONObject.NULL)
            root.put("settings", JSONObject().apply {
                put("enabled", value.settings.enabled)
                put("rings", value.settings.rings)
                put("maxSeconds", value.settings.maxSeconds)
                put("beep", value.settings.beep)
                put("ownerName", value.settings.ownerName)
                put("watchCalls", value.settings.watchCalls)
                put("script", value.settings.script)
            })
            root.put("greetings", JSONArray().apply {
                value.greetings.forEach { item ->
                    put(JSONObject().apply {
                        put("id", item.id)
                        put("name", item.name)
                        put("createdAt", item.createdAt)
                        put("durationMs", item.durationMs)
                        put("fileName", item.fileName)
                        put("source", item.source)
                        put("filters", filtersJson(item.filters))
                        put("peaks", JSONArray(item.peaks))
                    })
                }
            })
            root.put("messages", JSONArray().apply {
                value.messages.forEach { item ->
                    put(JSONObject().apply {
                        put("id", item.id)
                        put("caller", item.caller)
                        put("createdAt", item.createdAt)
                        put("durationMs", item.durationMs)
                        put("listened", item.listened)
                        put("fileName", item.fileName)
                        put("peaks", JSONArray(item.peaks))
                    })
                }
            })
            return root
        }

        private fun filtersJson(filters: Filters) = JSONObject().apply {
            put("clean", filters.clean)
            put("warmth", filters.warmth)
            put("clarity", filters.clarity)
            put("air", filters.air)
            put("deess", filters.deess)
            put("compress", filters.compress)
            put("room", filters.room)
        }

        private fun parseFilters(obj: JSONObject?): Filters {
            if (obj == null) return Filters()
            return Filters(
                clean = obj.optInt("clean", 32).coerceIn(0, 100),
                warmth = obj.optInt("warmth", 36).coerceIn(0, 100),
                clarity = obj.optInt("clarity", 30).coerceIn(0, 100),
                air = obj.optInt("air", 14).coerceIn(0, 100),
                deess = obj.optInt("deess", 26).coerceIn(0, 100),
                compress = obj.optInt("compress", 38).coerceIn(0, 100),
                room = obj.optInt("room", 4).coerceIn(0, 100),
            )
        }

        private fun parsePeaks(array: JSONArray?): List<Float> {
            if (array == null) return emptyList()
            return buildList {
                for (i in 0 until array.length().coerceAtMost(48)) {
                    add(array.optDouble(i).toFloat().coerceIn(0f, 1f))
                }
            }
        }

        private fun parse(root: JSONObject): Persisted {
            val settingsObj = root.optJSONObject("settings")
            val settings = sanitize(
                MachineSettings(
                    enabled = settingsObj?.optBoolean("enabled", true) ?: true,
                    rings = settingsObj?.optInt("rings", 4) ?: 4,
                    maxSeconds = settingsObj?.optInt("maxSeconds", 60) ?: 60,
                    beep = settingsObj?.optBoolean("beep", true) ?: true,
                    ownerName = settingsObj?.optString("ownerName").orEmpty(),
                    watchCalls = settingsObj?.optBoolean("watchCalls", true) ?: true,
                    script = settingsObj?.optString("script").orEmpty(),
                ),
            )
            val greetings = buildList {
                val array = root.optJSONArray("greetings") ?: JSONArray()
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val id = item.optString("id")
                    val fileName = safeName(item.optString("fileName"))
                    if (id.isBlank() || fileName.isEmpty()) continue
                    add(
                        Greeting(
                            id = id,
                            name = item.optString("name", "Saludo").take(40),
                            createdAt = item.optLong("createdAt", System.currentTimeMillis()),
                            durationMs = item.optInt("durationMs"),
                            filters = parseFilters(item.optJSONObject("filters")),
                            fileName = fileName,
                            source = item.optString("source", "mic"),
                            peaks = parsePeaks(item.optJSONArray("peaks")),
                        ),
                    )
                }
            }
            val messages = buildList {
                val array = root.optJSONArray("messages") ?: JSONArray()
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val id = item.optString("id")
                    val fileName = safeName(item.optString("fileName"))
                    if (id.isBlank() || fileName.isEmpty()) continue
                    add(
                        Voicemail(
                            id = id,
                            caller = item.optString("caller", "Desconocido").take(40),
                            createdAt = item.optLong("createdAt", System.currentTimeMillis()),
                            durationMs = item.optInt("durationMs"),
                            listened = item.optBoolean("listened"),
                            fileName = fileName,
                            peaks = parsePeaks(item.optJSONArray("peaks")),
                        ),
                    )
                }
            }
            val requested = if (root.isNull("activeGreetingId")) null else root.optString("activeGreetingId")
            val active = if (greetings.any { it.id == requested }) requested else greetings.firstOrNull()?.id
            return Persisted(settings, greetings, messages, active)
        }
    }
}
