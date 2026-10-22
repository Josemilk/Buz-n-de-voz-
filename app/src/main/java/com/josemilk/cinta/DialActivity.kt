package com.josemilk.cinta

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.telecom.TelecomManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.josemilk.cinta.call.granted
import com.josemilk.cinta.call.isDefaultDialer
import com.josemilk.cinta.ui.CintaTheme

class DialActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val seed = intent?.data?.schemeSpecificPart.orEmpty()
        setContent {
            CintaTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    DialPad(seed)
                }
            }
        }
    }
}

@Composable
private fun DialPad(seed: String) {
    val context = LocalContext.current
    var digits by remember { mutableStateOf(seed) }
    var note by remember { mutableStateOf("") }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) note = ""
    }
    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TextButton(onClick = { context.startActivity(Intent(context, MainActivity::class.java)) }) {
            Text("Abrir buzón")
        }
        Text(
            digits.ifBlank { "Marca un número" },
            modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.headlineMedium,
            color = if (digits.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onBackground,
        )
        if (note.isNotBlank()) Text(note, color = CoralColor, textAlign = TextAlign.Center)
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#")
        keys.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { key ->
                    Button(
                        onClick = { if (digits.length < 20) digits += key },
                        modifier = Modifier.weight(1f).aspectRatio(1.15f),
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                    ) { Text(key, style = MaterialTheme.typography.titleLarge) }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            IconButton(onClick = { digits = if (digits.isEmpty()) digits else digits.dropLast(1) }) {
                Icon(Icons.Filled.Backspace, contentDescription = "Borrar dígito")
            }
            Button(
                onClick = {
                    if (digits.isBlank()) return@Button
                    if (!context.granted(Manifest.permission.CALL_PHONE)) {
                        permission.launch(Manifest.permission.CALL_PHONE)
                        note = "Falta el permiso para llamar."
                        return@Button
                    }
                    val uri = Uri.fromParts("tel", digits, null)
                    try {
                        if (context.isDefaultDialer()) {
                            val telecom = context.getSystemService(TelecomManager::class.java)
                            telecom.placeCall(uri, Bundle())
                        } else {
                            context.startActivity(Intent(Intent.ACTION_CALL, uri))
                        }
                    } catch (_: SecurityException) {
                        note = "El teléfono no dejó hacer la llamada."
                    }
                },
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            ) {
                Icon(Icons.Filled.Call, contentDescription = "Llamar")
                Text("  Llamar")
            }
        }
    }
}

private val CoralColor = androidx.compose.ui.graphics.Color(0xFFFF6B57)
