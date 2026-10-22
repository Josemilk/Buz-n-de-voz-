package com.josemilk.cinta.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.josemilk.cinta.data.Voicemail
import com.josemilk.cinta.data.formatClock
import com.josemilk.cinta.data.formatWhen

private enum class Tab(val label: String) { Inbox("Buzón"), Studio("Estudio"), Settings("Ajustes") }

@Composable
fun CintaShell(vm: CintaViewModel) {
    val state by vm.state.collectAsState()
    val playing by vm.playingId.collectAsState()
    val banner by vm.banner.collectAsState()
    val simCaller by vm.simCaller.collectAsState()
    var tab by remember { mutableStateOf(Tab.Inbox) }
    val unread = state.messages.count { !it.listened }
    val scheme = MaterialTheme.colorScheme

    Box(Modifier.fillMaxSize().background(scheme.background)) {
        Scaffold(
            containerColor = scheme.background,
            bottomBar = {
                NavigationBar(containerColor = scheme.surface) {
                    NavItem(Tab.Inbox, unread, tab == Tab.Inbox) { tab = Tab.Inbox }
                    NavItem(Tab.Studio, 0, tab == Tab.Studio) { tab = Tab.Studio }
                    NavItem(Tab.Settings, 0, tab == Tab.Settings) { tab = Tab.Settings }
                }
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                Header(state.settings.enabled) { vm.updateSettings { it.copy(enabled = !it.enabled) } }
                if (banner != null) {
                    TextButton(onClick = vm::dismissBanner, modifier = Modifier.padding(horizontal = 12.dp)) {
                        Text(banner!!, color = scheme.onSurface)
                    }
                }
                when (tab) {
                    Tab.Inbox -> InboxPage(vm, state.messages, playing)
                    Tab.Studio -> StudioPage(vm)
                    Tab.Settings -> SettingsPage(vm)
                }
            }
        }
        if (simCaller != null) SimOverlay(vm, simCaller!!)
    }
}

@Composable
private fun RowScope.NavItem(tab: Tab, badge: Int, selected: Boolean, onClick: () -> Unit) {
    val icon = when (tab) {
        Tab.Inbox -> Icons.Filled.Inbox
        Tab.Studio -> Icons.Filled.GraphicEq
        Tab.Settings -> Icons.Filled.Settings
    }
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = {
            Box {
                Icon(icon, contentDescription = tab.label)
                if (badge > 0) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(Coral),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("$badge", color = Color.White, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        },
        label = { Text(tab.label) },
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = MaterialTheme.colorScheme.primary,
            selectedTextColor = MaterialTheme.colorScheme.primary,
            indicatorColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    )
}

@Composable
private fun Header(enabled: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Cinta", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onBackground)
            Text("Contestador en este aparato", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(if (enabled) "Activo" else "Apagado", color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Switch(checked = enabled, onCheckedChange = { onToggle() })
        }
    }
}

@Composable
private fun InboxPage(vm: CintaViewModel, messages: List<Voicemail>, playing: String?) {
    var pending by remember { mutableStateOf<String?>(null) }
    val ordered = remember(messages) { messages.sortedByDescending { it.createdAt } }
    LazyColumn(
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Prueba el contestador sin que nadie llame.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Desconocido", "Mamá", "Trabajo").forEach { name ->
                    Button(
                        onClick = { vm.simulate(name) },
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    ) { Text(name) }
                }
            }
        }
        if (ordered.isEmpty()) {
            item {
                CintaCard {
                    Text("El buzón está vacío", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Cuando una llamada suene los timbres que elegiste, Cinta intenta contestar, deja el saludo y graba. Todo queda en el teléfono.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        items(ordered, key = { it.id }) { message ->
            MessageCard(
                message = message,
                playing = playing == message.id,
                onPlay = { vm.playMessage(message.id) },
                onDelete = { pending = message.id },
            )
        }
    }
    if (pending != null) {
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("¿Borrar este mensaje?") },
            text = { Text("Se elimina del teléfono. No se puede recuperar.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteMessage(pending!!)
                    pending = null
                }) { Text("Borrar", color = Coral) }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun MessageCard(message: Voicemail, playing: Boolean, onPlay: () -> Unit, onDelete: () -> Unit) {
    val quiet = message.peaks.maxOrNull()?.let { it < 0.08f } == true
    CintaCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(message.caller, style = MaterialTheme.typography.titleMedium)
                Text(formatWhen(message.createdAt), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!message.listened) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(Coral))
            }
        }
        Spacer(Modifier.height(12.dp))
        Wave(message.peaks, if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(formatClock(message.durationMs), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            IconButton(onClick = onPlay) {
                Icon(if (playing) Icons.Filled.Stop else Icons.Filled.PlayArrow, contentDescription = if (playing) "Detener" else "Escuchar")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Borrar", tint = Coral)
            }
        }
        if (quiet) {
            Text(
                "Llegó casi en silencio. Algunos teléfonos no entregan la voz de la otra persona.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun Wave(peaks: List<Float>, color: Color, modifier: Modifier = Modifier) {
    val bars = if (peaks.isEmpty()) List(32) { 0.12f } else peaks
    Canvas(modifier.fillMaxWidth().height(42.dp)) {
        val gap = 3.dp.toPx()
        val w = ((size.width - gap * (bars.size - 1)) / bars.size).coerceAtLeast(2f)
        bars.forEachIndexed { index, peak ->
            val h = 4.dp.toPx() + (size.height - 4.dp.toPx()) * peak.coerceIn(0.05f, 1f)
            drawRoundRect(
                color = color,
                topLeft = Offset(index * (w + gap), (size.height - h) / 2f),
                size = Size(w, h),
                cornerRadius = CornerRadius(w / 2f, w / 2f),
            )
        }
    }
}

@Composable
private fun SimOverlay(vm: CintaViewModel, caller: String) {
    val phase by vm.simPhase.collectAsState()
    Column(
        Modifier
            .fillMaxSize()
            .background(SeaDeep)
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Llamada de prueba", color = Color.White.copy(alpha = 0.75f))
        Spacer(Modifier.height(12.dp))
        Text(
            caller,
            color = Color.White,
            style = MaterialTheme.typography.displaySmall.copy(fontStyle = FontStyle.Normal, fontFamily = FontFamily.Serif),
        )
        Spacer(Modifier.height(18.dp))
        Text(phase.ifBlank { "Sonando" }, color = Color(0xFFD8FFF4))
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = vm::answerSimNow,
            shape = RoundedCornerShape(20.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = SeaDeep),
        ) { Text("Contestar ya") }
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = vm::hangupSim) {
            Icon(Icons.Filled.Close, contentDescription = null, tint = Color.White)
            Spacer(Modifier.width(6.dp))
            Text("Colgar", color = Color.White)
        }
    }
}
