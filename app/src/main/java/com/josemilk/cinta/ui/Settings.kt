package com.josemilk.cinta.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.role.RoleManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.telecom.TelecomManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.josemilk.cinta.DialActivity
import com.josemilk.cinta.call.ServiceStarter
import com.josemilk.cinta.call.granted
import com.josemilk.cinta.call.isDefaultDialer
import com.josemilk.cinta.data.DURATION_OPTIONS

@Composable
fun SettingsPage(vm: CintaViewModel) {
    val state by vm.state.collectAsState()
    val settings = state.settings
    val context = LocalContext.current
    var confirmClear by remember { mutableStateOf(false) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        ServiceStarter.sync(context)
    }
    val roleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        ServiceStarter.sync(context)
    }
    val dialer = context.isDefaultDialer()
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Ajustes", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Cuántos timbres espera y cuánto puede durar un mensaje. Nada sale del teléfono.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        CintaCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Vigilar llamadas", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (settings.watchCalls) "Escucha el timbre aunque Cinta no esté abierta." else "Solo funcionan las pruebas del buzón.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.watchCalls,
                    onCheckedChange = { on -> vm.updateSettings { it.copy(watchCalls = on) } },
                )
            }
        }
        CintaCard {
            Text("Timbres antes de contestar", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                IconButton(onClick = { vm.updateSettings { it.copy(rings = it.rings - 1) } }, enabled = settings.rings > 1) {
                    Icon(Icons.Filled.Remove, contentDescription = "Menos timbres")
                }
                Text(
                    "${settings.rings}",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                IconButton(onClick = { vm.updateSettings { it.copy(rings = it.rings + 1) } }, enabled = settings.rings < 8) {
                    Icon(Icons.Filled.Add, contentDescription = "Más timbres")
                }
            }
            Text(
                "Android no avisa cada timbre por separado. Cinta cuenta un timbre cada 5 segundos de llamada.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        CintaCard {
            Text("Duración máxima del mensaje", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DURATION_OPTIONS.forEach { (seconds, label) ->
                    val on = settings.maxSeconds == seconds
                    if (on) {
                        Button(onClick = {}, shape = RoundedCornerShape(14.dp), contentPadding = ButtonDefaults.ContentPadding) {
                            Text(label)
                        }
                    } else {
                        FilledTonalButton(
                            onClick = { vm.updateSettings { it.copy(maxSeconds = seconds) } },
                            shape = RoundedCornerShape(14.dp),
                        ) { Text(label) }
                    }
                }
            }
        }
        CintaCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Tono después del saludo", style = MaterialTheme.typography.titleMedium)
                    Text("Avisa cuándo empezar a hablar.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = settings.beep, onCheckedChange = { on -> vm.updateSettings { it.copy(beep = on) } })
            }
        }
        CintaCard {
            Text("Tu nombre", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            TextField(
                value = settings.ownerName,
                onValueChange = { value -> vm.updateSettings { it.copy(ownerName = value) } },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Opcional") },
                singleLine = true,
            )
        }
        CintaCard {
            Text("Permisos", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "El micrófono graba saludos y mensajes. El teléfono permite saber que está sonando. Para descolgar de verdad, Cinta tiene que ser la aplicación de teléfono.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = {
                    val wanted = mutableListOf(
                        Manifest.permission.RECORD_AUDIO,
                        Manifest.permission.READ_PHONE_STATE,
                        Manifest.permission.ANSWER_PHONE_CALLS,
                        Manifest.permission.CALL_PHONE,
                        Manifest.permission.READ_CALL_LOG,
                    )
                    if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.POST_NOTIFICATIONS
                    permissions.launch(wanted.filter { !context.granted(it) }.toTypedArray())
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
            ) { Text(if (context.granted(Manifest.permission.RECORD_AUDIO)) "Revisar permisos" else "Dar permisos") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    val intent = dialerIntent(context)
                    if (intent == null) return@OutlinedButton
                    roleLauncher.launch(intent)
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
            ) { Text(if (dialer) "Cinta ya es la app de teléfono" else "Usar Cinta como teléfono") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { context.startActivity(Intent(context, DialActivity::class.java)) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
            ) { Text("Abrir teclado") }
            Spacer(Modifier.height(8.dp))
            val power = context.getSystemService(PowerManager::class.java)
            val free = power.isIgnoringBatteryOptimizations(context.packageName)
            if (!free) {
                TextButton(onClick = { askBattery(context) }) {
                    Text("Evitar que el sistema cierre el contestador")
                }
            }
        }
        CintaCard {
            Text("Qué puede hacer Android", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "Si Cinta es la app de teléfono, descuelga después de los timbres, reproduce el saludo y graba. Varios fabricantes no dejan meter el audio dentro de la llamada ni grabar a la otra persona: en ese caso el buzón, el estudio y las pruebas siguen funcionando por completo, sin internet.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(
            onClick = { confirmClear = true },
            modifier = Modifier.fillMaxWidth(),
            enabled = state.messages.isNotEmpty(),
            shape = RoundedCornerShape(16.dp),
        ) { Text("Borrar todo el historial") }
        Spacer(Modifier.height(18.dp))
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("¿Vaciar el buzón?") },
            text = { Text("Se borran todos los mensajes de este teléfono.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.clearMessages()
                    confirmClear = false
                }) { Text("Vaciar", color = Coral) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancelar") } },
        )
    }
}

@SuppressLint("BatteryLife")
private fun askBattery(context: android.content.Context) {
    try {
        context.startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
        )
    } catch (_: Exception) {
        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }
}

private fun dialerIntent(context: android.content.Context): Intent? {
    if (context.isDefaultDialer()) return null
    return if (Build.VERSION.SDK_INT >= 29) {
        val roles = context.getSystemService(RoleManager::class.java)
        if (roles.isRoleAvailable(RoleManager.ROLE_DIALER)) roles.createRequestRoleIntent(RoleManager.ROLE_DIALER) else null
    } else {
        @Suppress("DEPRECATION")
        Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER)
            .putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, context.packageName)
    }
}

