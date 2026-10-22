package com.josemilk.cinta.call

import android.telecom.Call
import kotlinx.coroutines.flow.MutableStateFlow

object CallHub {
    val revision = MutableStateFlow(0)
    private val items = mutableListOf<Call>()
    private val lock = Any()

    fun add(call: Call) {
        synchronized(lock) {
            items.add(call)
            call.registerCallback(object : Call.Callback() {
                override fun onStateChanged(call: Call, state: Int) {
                    bump()
                }
            })
            bump()
        }
    }

    fun remove(call: Call) {
        synchronized(lock) {
            items.remove(call)
            bump()
        }
    }

    fun primary(): Call? = synchronized(lock) {
        items.firstOrNull { it.state == Call.STATE_RINGING }
            ?: items.firstOrNull { it.state != Call.STATE_DISCONNECTED }
            ?: items.firstOrNull()
    }

    fun snapshot(): List<Call> = synchronized(lock) { items.toList() }

    private fun bump() {
        revision.value = revision.value + 1
    }
}

object MachineBus {
    val phase = MutableStateFlow("")
    val running = MutableStateFlow(false)
    val countdownSec = MutableStateFlow(-1)

    @Volatile
    var wantsMachine: Boolean = false
}
