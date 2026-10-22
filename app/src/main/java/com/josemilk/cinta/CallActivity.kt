package com.josemilk.cinta

import android.os.Build
import android.os.Bundle
import android.telecom.Call
import android.telecom.VideoProfile
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.josemilk.cinta.call.AnsweringService
import com.josemilk.cinta.call.CallHub
import com.josemilk.cinta.call.CintaInCallService
import com.josemilk.cinta.call.MachineBus
import com.josemilk.cinta.data.Vault
import com.josemilk.cinta.ui.CintaTheme
import kotlinx.coroutines.delay

class CallActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        setContent {
            CintaTheme {
                LiveCall(onClose = { finish() })
            }
        }
    }
}

@Composable
private fun LiveCall(onClose: () -> Unit) {
    val revision by CallHub.revision.collectAsState()
    val phase by MachineBus.phase.collectAsState()
    val running by MachineBus.running.collectAsState()
    val countdown by MachineBus.countdownSec.collectAsState()
    val call = CallHub.primary()
    val settings = Vault.get(androidx.compose.ui.platform.LocalContext.current).snapshot().settings
    LaunchedEffect(revision, call?.state) {
        val left = CallHub.snapshot()
        if (left.isEmpty() || left.all { it.state == Call.STATE_DISCONNECTED }) {
            delay(700)
            if (CallHub.snapshot().all { it.state == Call.STATE_DISCONNECTED }) onClose()
        }
    }
    Column(
        Modifier.fillMaxSize().background(Color(0xFF08352C)).padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Cinta", color = Color(0xFF9BE7D6))
        Spacer(Modifier.height(16.dp))
        Text(
            if (call == null) "Conectando…" else AnsweringService.callerLabel(call),
            color = Color.White,
            style = MaterialTheme.typography.displaySmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(statusLine(call, phase, running, countdown, settings.enabled), color = Color(0xFFD7F6EE), textAlign = TextAlign.Center)
        if (settings.ownerName.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(settings.ownerName, color = Color.White.copy(alpha = 0.7f))
        }
        Spacer(Modifier.height(28.dp))
        if (call != null && call.state == Call.STATE_RINGING) {
            Button(
                onClick = {
                    MachineBus.wantsMachine = true
                    call.answer(VideoProfile.STATE_AUDIO_ONLY)
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF08352C)),
            ) { Text("Que conteste Cinta") }
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = {
                    MachineBus.wantsMachine = false
                    call.answer(VideoProfile.STATE_AUDIO_ONLY)
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
            ) { Text("Contestar yo") }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = { call.reject(false, null) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
            ) { Text("Rechazar", color = Color.White) }
        }
        if (call != null && (call.state == Call.STATE_ACTIVE || call.state == Call.STATE_DIALING || call.state == Call.STATE_HOLDING)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { CintaInCallService.speaker(true) }, shape = RoundedCornerShape(16.dp)) {
                    Text("Altavoz", color = Color.White)
                }
                OutlinedButton(onClick = { CintaInCallService.mute(true) }, shape = RoundedCornerShape(16.dp)) {
                    Text("Silencio", color = Color.White)
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { call.disconnect() },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6B57)),
            ) { Text("Colgar") }
        }
    }
}

private fun statusLine(call: Call?, phase: String, running: Boolean, countdown: Int, enabled: Boolean): String {
    if (phase.isNotBlank()) return phase
    if (running) return "Contestador en curso"
    return when (call?.state) {
        Call.STATE_RINGING -> if (enabled && countdown >= 0) "Cinta contestará en ${countdown}s" else "Llamada entrante"
        Call.STATE_DIALING -> "Llamando"
        Call.STATE_ACTIVE -> "En llamada"
        Call.STATE_HOLDING -> "En espera"
        Call.STATE_DISCONNECTED -> "Colgó"
        else -> "Llamada"
    }
}
