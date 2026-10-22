package com.josemilk.cinta.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.josemilk.cinta.data.FILTER_FIELDS
import com.josemilk.cinta.data.PRESETS
import com.josemilk.cinta.data.formatClock

@Composable
fun StudioPage(vm: CintaViewModel) {
    val state by vm.state.collectAsState()
    val playing by vm.playingId.collectAsState()
    val recording by vm.recording.collectAsState()
    val seconds by vm.recordSeconds.collectAsState()
    val level by vm.level.collectAsState()
    var pending by remember { mutableStateOf<String?>(null) }
    var script by remember(state.settings.script) { mutableStateOf(state.settings.script) }
    val active = state.greetings.find { it.id == state.activeGreetingId } ?: state.greetings.firstOrNull()
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importGreeting(uri)
    }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Estudio", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Graba, elige o borra el saludo. Los filtros se aplican aquí, sin subir nada a internet.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (active != null) {
            CintaCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(active.name, style = MaterialTheme.typography.titleLarge)
                        Text(
                            "${formatClock(active.durationMs)} · en uso",
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    IconButton(onClick = { vm.playGreeting(active.id) }) {
                        Icon(
                            if (playing == active.id) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                            contentDescription = "Escuchar saludo",
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Wave(
                    if (recording) List(32) { index -> if (index > 28) level.coerceAtLeast(0.08f) else 0.12f } else active.peaks,
                    MaterialTheme.colorScheme.primary,
                )
            }
            Text("Arreglo de voz", style = MaterialTheme.typography.titleMedium)
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PRESETS.forEach { preset ->
                    OutlinedButton(
                        onClick = { vm.updateFilters(active.id, preset.filters) },
                        shape = RoundedCornerShape(16.dp),
                    ) { Text(preset.name) }
                }
            }
            Button(
                onClick = { vm.fixVoice(active.id) },
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Coral),
            ) { Text("Arreglar voz") }
            FILTER_FIELDS.forEach { field ->
                val value = when (field.key) {
                    "clean" -> active.filters.clean
                    "warmth" -> active.filters.warmth
                    "clarity" -> active.filters.clarity
                    "air" -> active.filters.air
                    "deess" -> active.filters.deess
                    "compress" -> active.filters.compress
                    else -> active.filters.room
                }
                Column {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(field.label, style = MaterialTheme.typography.titleMedium)
                        Text("$value")
                    }
                    Slider(
                        value = value.toFloat(),
                        onValueChange = { vm.updateFilters(active.id, active.filters.with(field.key, it.toInt())) },
                        valueRange = 0f..100f,
                    )
                    Text(field.hint, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        CintaCard {
            Text(if (recording) "Grabando ${formatClock(seconds * 1000)}" else "Nuevo saludo", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = vm::toggleRecord,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(if (recording) Icons.Filled.Stop else Icons.Filled.Mic, contentDescription = null)
                Text(if (recording) "  Detener" else "  Grabar con el micrófono")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { importer.launch(arrayOf("audio/*")) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
            ) { Text("Elegir un audio del teléfono") }
        }
        CintaCard {
            Text("Texto del saludo", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            TextField(
                value = script,
                onValueChange = { script = it.take(240) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    vm.updateSettings { it.copy(script = script) }
                    vm.createSpoken(script)
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
            ) { Text("Decirlo con la voz del teléfono") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = vm::createSynth,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
            ) { Text("Crear voz de prueba") }
        }
        if (state.greetings.size > 1 || active != null) {
            Text("Saludos guardados", style = MaterialTheme.typography.titleMedium)
            state.greetings.forEach { greeting ->
                val selected = greeting.id == state.activeGreetingId
                CintaCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(greeting.name, style = MaterialTheme.typography.titleMedium)
                            Text(formatClock(greeting.durationMs), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (selected) Icon(Icons.Filled.Check, contentDescription = "En uso", tint = MaterialTheme.colorScheme.primary)
                        TextButton(onClick = { vm.selectGreeting(greeting.id) }) { Text("Usar") }
                        IconButton(onClick = { pending = greeting.id }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Eliminar", tint = Coral)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
    if (pending != null) {
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("¿Eliminar este saludo?") },
            text = { Text("Si era el que contestaba, Cinta usará otro o la voz de prueba.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteGreeting(pending!!)
                    pending = null
                }) { Text("Eliminar", color = Coral) }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancelar") } },
        )
    }
}
