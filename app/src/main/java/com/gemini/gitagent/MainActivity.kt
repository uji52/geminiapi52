package com.gemini.gitagent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.gemini.gitagent.ui.AgentViewModel
import com.gemini.gitagent.ui.SettingsViewModel
import com.gemini.gitagent.ui.screens.ChatScreen
import com.gemini.gitagent.ui.screens.SettingsScreen
import com.gemini.gitagent.ui.theme.GeminiGitAgentTheme

class MainActivity : ComponentActivity() {

    private val settingsViewModel: SettingsViewModel by viewModels()
    private val agentViewModel: AgentViewModel by viewModels()

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            GeminiGitAgentTheme {
                var selectedTabIndex by remember { mutableIntStateOf(0) }
                val settingsState by settingsViewModel.uiState.collectAsState()

                Scaffold(
                    topBar = {
                        val repoText = if (settingsState.settings.githubRepo.isNotEmpty()) {
                            "${settingsState.settings.githubOwner}/${settingsState.settings.githubRepo}"
                        } else {
                            "Gemini Git Agent"
                        }
                        TopAppBar(
                            title = {
                                Text(repoText)
                            },
                            colors = TopAppBarDefaults.topAppBarColors()
                        )
                    },
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(
                                selected = selectedTabIndex == 0,
                                onClick = { selectedTabIndex = 0 },
                                icon = { Icon(Icons.Default.Chat, contentDescription = "Agent") },
                                label = { Text("Agent") }
                            )
                            NavigationBarItem(
                                selected = selectedTabIndex == 1,
                                onClick = { selectedTabIndex = 1 },
                                icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                                label = { Text("Settings") }
                            )
                        }
                    }
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        when (selectedTabIndex) {
                            0 -> ChatScreen(
                                viewModel = agentViewModel,
                                onNavigateToSettings = { selectedTabIndex = 1 }
                            )
                            1 -> SettingsScreen(
                                viewModel = settingsViewModel
                            )
                        }
                    }
                }
            }
        }
    }
}
