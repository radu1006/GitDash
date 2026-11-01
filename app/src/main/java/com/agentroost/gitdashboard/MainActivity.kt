package com.agentroost.gitdashboard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.agentroost.gitdashboard.ui.GitDashboardApp
import com.agentroost.gitdashboard.ui.theme.GitDashboardTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = (application as GitDashboardApplication).container

        setContent {
            GitDashboardTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    GitDashboardApp(container = container)
                }
            }
        }
    }
}
