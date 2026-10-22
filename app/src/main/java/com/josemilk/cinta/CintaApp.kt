package com.josemilk.cinta

import android.app.Application
import com.josemilk.cinta.data.Vault

class CintaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Vault.get(this)
    }
}
