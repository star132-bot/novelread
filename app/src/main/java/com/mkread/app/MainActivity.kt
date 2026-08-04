package com.mkread.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.mkread.app.navigation.MkreadNavHost
import com.mkread.app.ui.theme.MkreadTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as MkreadApplication).container
        setContent {
            MkreadTheme {
                MkreadNavHost(container = container)
            }
        }
    }
}
