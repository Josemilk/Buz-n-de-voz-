package com.josemilk.cinta

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.josemilk.cinta.call.ServiceStarter
import com.josemilk.cinta.ui.CintaShell
import com.josemilk.cinta.ui.CintaTheme
import com.josemilk.cinta.ui.CintaViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CintaTheme {
                val vm: CintaViewModel = viewModel()
                CintaShell(vm)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ServiceStarter.sync(this)
    }
}
