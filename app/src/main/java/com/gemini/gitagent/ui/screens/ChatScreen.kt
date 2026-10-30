package com.gemini.gitagent.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gemini.gitagent.ui.AgentLogItem
import com.gemini.gitagent.ui.AgentViewModel
import com.gemini.gitagent.ui.LogType

@Composable
fun ChatScreen(
    viewModel: AgentViewModel,
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val listState = rememberLazyListState()

    // Auto-scroll to bottom on new log entry
    LaunchedEffect(uiState.logs.size) {
        if (uiState.logs.isNotEmpty()) {
            listState.animateScrollToItem(uiState.logs.size - 1)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.refreshConfigStatus()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(12.dp)
    ) {
        // Warning banner if settings are missing
        if (!uiState.isConfigured) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF7E0)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = null,
                        tint = Color(0xFFB06000),
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text(
                        text = "API Key or GitHub Token not configured yet.",
                        color = Color(0xFF7A4100),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    FilledTonalButton(
                        onClick = onNavigateToSettings,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text("Open Settings", fontSize = 12.sp)
                    }
                }
            }
        }

        // Pull Request Card if PR is created
        uiState.latestPrUrl?.let { prUrl ->
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFFE6F4EA)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = Color(0xFF137333),
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text(
                            text = "Pull Request Created!",
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF137333),
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    uiState.latestPrTitle?.let { title ->
                        Text(
                            text = title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFF0D652D),
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                    Button(
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(prUrl))
                            context.startActivity(intent)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF137333)),
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Icon(imageVector = Icons.Default.OpenInBrowser, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("View on GitHub")
                    }
                }
            }
        }

        // Logs display list
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    shape = RoundedCornerShape(8.dp)
                )
                .padding(8.dp)
        ) {
            if (uiState.logs.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Ready to start coding tasks.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "Ask Gemini to read code, commit changes, and open a PR.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(uiState.logs, key = { it.id }) { log ->
                        LogItemView(log = log)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Quick prompt chips
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            val suggestions = listOf(
                "READMEに概要と使用手順を追記してPR起票",
                "MITライセンスファイル(LICENSE)を追加してPR起票",
                "CONTRIBUTING.mdを作成してPR起票"
            )
            items(suggestions) { text ->
                AssistChip(
                    onClick = { viewModel.onPromptChange(text) },
                    label = { Text(text, maxLines = 1, fontSize = 12.sp) }
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Input & Action Area
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = uiState.prompt,
                onValueChange = { viewModel.onPromptChange(it) },
                label = { Text("Task Instruction for Gemini") },
                placeholder = { Text("例: XXXの不具合を修正してPRを作成して") },
                maxLines = 4,
                modifier = Modifier.weight(1f),
                enabled = !uiState.isRunning
            )

            if (uiState.isRunning) {
                Button(
                    onClick = { viewModel.stopTask() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(imageVector = Icons.Default.Stop, contentDescription = "Stop")
                }
            } else {
                Button(
                    onClick = { viewModel.runTask() },
                    enabled = uiState.prompt.isNotBlank()
                ) {
                    Icon(imageVector = Icons.Default.Send, contentDescription = "Run")
                }
            }

            if (uiState.logs.isNotEmpty() && !uiState.isRunning) {
                IconButton(onClick = { viewModel.clearLogs() }) {
                    Icon(imageVector = Icons.Default.Clear, contentDescription = "Clear logs")
                }
            }
        }
    }
}

@Composable
fun LogItemView(log: AgentLogItem) {
    val (bg, contentColor, icon) = when (log.type) {
        LogType.INFO -> Triple(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.onSurface, Icons.Default.Info)
        LogType.STATUS -> Triple(Color(0xFFE8F0FE), Color(0xFF1967D2), Icons.Default.Info)
        LogType.TOOL -> Triple(Color(0xFFF1F3F4), Color(0xFF202124), Icons.Default.Build)
        LogType.PR -> Triple(Color(0xFFE6F4EA), Color(0xFF137333), Icons.Default.Check)
        LogType.SUCCESS -> Triple(Color(0xFFE6F4EA), Color(0xFF137333), Icons.Default.Check)
        LogType.ERROR -> Triple(Color(0xFFFCE8E6), Color(0xFFC5221F), Icons.Default.ErrorOutline)
    }

    Surface(
        color = bg,
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.Top
        ) {
            Text(
                text = log.timestamp,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = contentColor.copy(alpha = 0.6f),
                modifier = Modifier.padding(end = 6.dp, top = 2.dp)
            )
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier
                    .size(16.dp)
                    .padding(end = 4.dp, top = 2.dp)
            )
            Text(
                text = log.message,
                color = contentColor,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                modifier = Modifier.weight(1f)
            )
        }
    }
}
