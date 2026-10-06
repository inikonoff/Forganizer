package com.forganizer.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.forganizer.app.ui.App
import com.forganizer.app.ui.MainViewModel
import com.forganizer.app.ui.ForganizerTheme

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ForganizerTheme {
                App(vm)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Returning from the system "All files access" screen or after a permission was revoked.
        vm.onResume()
    }
}
