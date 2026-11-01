package com.gemini.gitagent.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.gemini.gitagent.data.repository.GeminiModelInfo
import com.gemini.gitagent.ui.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val scrollState = rememberScrollState()
    val focusManager = LocalFocusManager.current

    var geminiApiKey by remember(uiState.settings.geminiApiKey) { mutableStateOf(uiState.settings.geminiApiKey) }
    var geminiModel by remember(uiState.settings.geminiModel) { mutableStateOf(uiState.settings.geminiModel) }
    var githubToken by remember(uiState.settings.githubToken) { mutableStateOf(uiState.settings.githubToken) }
    var githubOwner by remember(uiState.settings.githubOwner) { mutableStateOf(uiState.settings.githubOwner) }
    var githubRepo by remember(uiState.settings.githubRepo) { mutableStateOf(uiState.settings.githubRepo) }
    var githubBaseBranch by remember(uiState.settings.githubBaseBranch) { mutableStateOf(uiState.settings.githubBaseBranch) }

    var isGeminiKeyVisible by remember { mutableStateOf(false) }
    var isGitHubTokenVisible by remember { mutableStateOf(false) }

    var modelDropdownExpanded by remember { mutableStateOf(false) }
    var repoDropdownExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Security Banner
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 8.dp, top = 2.dp)
                )
                Column {
                    Text(
                        text = "Credential Security",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Keys and tokens are encrypted with AES-256 on this device only. They are never committed to Git nor sent to third-party servers.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        // Section: Google AI (Gemini)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Google AI Studio Settings",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            if (geminiApiKey.isNotBlank()) {
                OutlinedButton(
                    onClick = { viewModel.fetchGeminiModels(geminiApiKey) },
                    enabled = !uiState.isFetchingModels
                ) {
                    if (uiState.isFetchingModels) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Fetch Models", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }

        // Free Tier Guidance Banner
        Card(
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFFE6F4EA)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = Color(0xFF137333),
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "Google AI Studio 無料枠ガイド",
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF137333),
                        style = MaterialTheme.typography.labelMedium
                    )
                    Text(
                        text = "「flash」系モデルは事前クレジット残高 $0 でも無料枠で利用できます。「pro / argon」系は有料クレジットが必要です。",
                        color = Color(0xFF137333),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        OutlinedTextField(
            value = geminiApiKey,
            onValueChange = {
                geminiApiKey = it
                if (it.isNotBlank()) {
                    viewModel.fetchGeminiModels(it, debounceMs = 1200)
                }
            },
            label = { Text("Gemini API Key") },
            placeholder = { Text("AIzaSy...") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = if (isGeminiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Next
            ),
            trailingIcon = {
                IconButton(onClick = { isGeminiKeyVisible = !isGeminiKeyVisible }) {
                    Icon(
                        imageVector = if (isGeminiKeyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = "Toggle visibility"
                    )
                }
            }
        )

        // Gemini Model - Selectable Dropdown
        ExposedDropdownMenuBox(
            expanded = modelDropdownExpanded,
            onExpandedChange = { modelDropdownExpanded = it }
        ) {
            OutlinedTextField(
                value = geminiModel,
                onValueChange = { geminiModel = it },
                label = { Text("Gemini Model (Select or Type)") },
                placeholder = { Text("gemini-3.5-flash") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                trailingIcon = {
                    if (uiState.isFetchingModels) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelDropdownExpanded)
                    }
                },
                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryEditable)
            )

            ExposedDropdownMenu(
                expanded = modelDropdownExpanded,
                onDismissRequest = { modelDropdownExpanded = false }
            ) {
                uiState.availableModelInfos.forEach { modelInfo ->
                    DropdownMenuItem(
                        text = {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = modelInfo.id,
                                        fontWeight = if (modelInfo.isRecommended) FontWeight.Bold else FontWeight.Medium,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    // Tier Badge
                                    Surface(
                                        color = when {
                                            modelInfo.isRecommended -> Color(0xFF137333)
                                            modelInfo.isFreeTier -> Color(0xFFE6F4EA)
                                            modelInfo.id.contains("tts") -> Color(0xFFF1F3F4)
                                            else -> Color(0xFFFCE8E6)
                                        },
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Text(
                                            text = modelInfo.tierLabel,
                                            color = when {
                                                modelInfo.isRecommended -> Color.White
                                                modelInfo.isFreeTier -> Color(0xFF137333)
                                                modelInfo.id.contains("tts") -> Color(0xFF5F6368)
                                                else -> Color(0xFFC5221F)
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = modelInfo.description,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        onClick = {
                            geminiModel = modelInfo.id
                            modelDropdownExpanded = false
                        }
                    )
                }
            }
        }

        // Selected Model Tier Status Banner
        val currentModelInfo = remember(geminiModel) { GeminiModelInfo.fromModelId(geminiModel) }
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (currentModelInfo.isFreeTier) Color(0xFFE6F4EA) else Color(0xFFFCE8E6)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (currentModelInfo.isFreeTier) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null,
                    tint = if (currentModelInfo.isFreeTier) Color(0xFF137333) else Color(0xFFC5221F),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = if (currentModelInfo.isFreeTier) "【無料枠で利用可能】${currentModelInfo.tierLabel}" else "【有料 / 要クレジット】${currentModelInfo.tierLabel}",
                        color = if (currentModelInfo.isFreeTier) Color(0xFF137333) else Color(0xFFC5221F),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelMedium
                    )
                    Text(
                        text = currentModelInfo.description,
                        color = if (currentModelInfo.isFreeTier) Color(0xFF137333) else Color(0xFFC5221F),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Section: GitHub
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "GitHub Settings",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            if (githubToken.isNotBlank()) {
                OutlinedButton(
                    onClick = { viewModel.fetchRepositories(githubToken, githubOwner) },
                    enabled = !uiState.isFetchingRepos
                ) {
                    if (uiState.isFetchingRepos) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Fetch Repos", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }

        OutlinedTextField(
            value = githubToken,
            onValueChange = {
                githubToken = it
                if (it.isNotBlank()) {
                    viewModel.fetchRepositories(it, githubOwner, debounceMs = 1200)
                }
            },
            label = { Text("GitHub Personal Access Token (PAT)") },
            placeholder = { Text("ghp_... (with 'repo' scope)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = if (isGitHubTokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Next
            ),
            trailingIcon = {
                IconButton(onClick = { isGitHubTokenVisible = !isGitHubTokenVisible }) {
                    Icon(
                        imageVector = if (isGitHubTokenVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = "Toggle visibility"
                    )
                }
            }
        )

        OutlinedTextField(
            value = githubOwner,
            onValueChange = {
                githubOwner = it
                if (githubToken.isNotBlank()) {
                    viewModel.fetchRepositories(githubToken, it, debounceMs = 1200)
                }
            },
            label = { Text("Owner (User/Org)") },
            placeholder = { Text("e.g. uji52") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth()
        )

        // Repository Name - Selectable Dropdown
        ExposedDropdownMenuBox(
            expanded = repoDropdownExpanded,
            onExpandedChange = { repoDropdownExpanded = it }
        ) {
            OutlinedTextField(
                value = githubRepo,
                onValueChange = { githubRepo = it },
                label = { Text("Repository Name (Select or Type)") },
                placeholder = {
                    Text(
                        if (uiState.availableRepositories.isEmpty()) {
                            "e.g. geminiapi52"
                        } else {
                            "Select from list or type"
                        }
                    )
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                trailingIcon = {
                    if (uiState.isFetchingRepos) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = repoDropdownExpanded)
                    }
                },
                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryEditable)
            )

            if (uiState.availableRepositories.isNotEmpty()) {
                ExposedDropdownMenu(
                    expanded = repoDropdownExpanded,
                    onDismissRequest = { repoDropdownExpanded = false }
                ) {
                    uiState.availableRepositories.forEach { repoOption ->
                        DropdownMenuItem(
                            text = { Text(repoOption) },
                            onClick = {
                                githubRepo = repoOption
                                repoDropdownExpanded = false
                            }
                        )
                    }
                }
            }
        }

        OutlinedTextField(
            value = githubBaseBranch,
            onValueChange = { githubBaseBranch = it },
            label = { Text("Default Base Branch") },
            placeholder = { Text("master") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            modifier = Modifier.fillMaxWidth()
        )

        // Buttons: Save & Test
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = {
                    focusManager.clearFocus()
                    viewModel.updateSettings(
                        geminiApiKey = geminiApiKey,
                        geminiModel = geminiModel,
                        githubToken = githubToken,
                        githubOwner = githubOwner,
                        githubRepo = githubRepo,
                        githubBaseBranch = githubBaseBranch
                    )
                },
                modifier = Modifier.weight(1f)
            ) {
                Icon(imageVector = Icons.Default.Save, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text("Save")
            }

            OutlinedButton(
                onClick = {
                    focusManager.clearFocus()
                    viewModel.updateSettings(
                        geminiApiKey = geminiApiKey,
                        geminiModel = geminiModel,
                        githubToken = githubToken,
                        githubOwner = githubOwner,
                        githubRepo = githubRepo,
                        githubBaseBranch = githubBaseBranch
                    )
                    viewModel.testConnection()
                },
                enabled = !uiState.isTesting,
                modifier = Modifier.weight(1f)
            ) {
                if (uiState.isTesting) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(imageVector = Icons.Default.Refresh, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Test Connection")
                }
            }
        }

        // Test result banner
        uiState.testMessage?.let { msg ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (uiState.isTestSuccess) Color(0xFFE6F4EA) else Color(0xFFFCE8E6)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (uiState.isTestSuccess) Icons.Default.CheckCircle else Icons.Default.Error,
                        contentDescription = null,
                        tint = if (uiState.isTestSuccess) Color(0xFF137333) else Color(0xFFC5221F),
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text(
                        text = msg,
                        color = if (uiState.isTestSuccess) Color(0xFF137333) else Color(0xFFC5221F),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        // Extra spacing at the bottom so elements scroll nicely above software keyboard
        Spacer(modifier = Modifier.height(64.dp))
    }
}
