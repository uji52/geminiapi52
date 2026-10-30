package com.gemini.gitagent.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gemini.gitagent.data.repository.AgentEvent
import com.gemini.gitagent.data.repository.AgentRepository
import com.gemini.gitagent.data.repository.SettingsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class LogType {
    INFO,
    STATUS,
    TOOL,
    PR,
    SUCCESS,
    ERROR
}

data class AgentLogItem(
    val id: Long = System.currentTimeMillis() + (0..999).random(),
    val timestamp: String,
    val type: LogType,
    val message: String
)

data class AgentUiState(
    val prompt: String = "",
    val isRunning: Boolean = false,
    val isConfigured: Boolean = false,
    val logs: List<AgentLogItem> = emptyList(),
    val latestPrUrl: String? = null,
    val latestPrTitle: String? = null,
    val latestSummary: String? = null
)

class AgentViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsRepository = SettingsRepository(application)
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val _uiState = MutableStateFlow(
        AgentUiState(isConfigured = settingsRepository.getSettings().isConfigured)
    )
    val uiState: StateFlow<AgentUiState> = _uiState.asStateFlow()

    private var activeJob: Job? = null

    fun refreshConfigStatus() {
        val configured = settingsRepository.getSettings().isConfigured
        _uiState.value = _uiState.value.copy(isConfigured = configured)
    }

    fun onPromptChange(newPrompt: String) {
        _uiState.value = _uiState.value.copy(prompt = newPrompt)
    }

    fun clearLogs() {
        _uiState.value = _uiState.value.copy(
            logs = emptyList(),
            latestPrUrl = null,
            latestPrTitle = null,
            latestSummary = null
        )
    }

    fun runTask() {
        val currentPrompt = _uiState.value.prompt.trim()
        if (currentPrompt.isEmpty()) return

        val settings = settingsRepository.getSettings()
        if (!settings.isConfigured) {
            addLog(LogType.ERROR, "Settings are not configured. Please open Settings and enter your Gemini API Key, GitHub Token, and Repo details.")
            return
        }

        activeJob?.cancel()
        _uiState.value = _uiState.value.copy(
            isRunning = true,
            latestPrUrl = null,
            latestPrTitle = null,
            latestSummary = null
        )

        addLog(LogType.INFO, "Starting task: \"$currentPrompt\"")

        activeJob = viewModelScope.launch {
            val agentRepo = AgentRepository(settings)
            try {
                agentRepo.executeTask(currentPrompt).collect { event ->
                    when (event) {
                        is AgentEvent.Status -> {
                            addLog(LogType.STATUS, event.message)
                        }
                        is AgentEvent.ToolCall -> {
                            addLog(LogType.TOOL, "Calling tool: ${event.name} (${event.detail})")
                        }
                        is AgentEvent.ToolResult -> {
                            addLog(LogType.TOOL, "Tool result [${event.name}]: ${event.summary}")
                        }
                        is AgentEvent.PullRequestCreated -> {
                            addLog(LogType.PR, "Pull Request Created! #${event.number} - ${event.url}")
                            _uiState.value = _uiState.value.copy(
                                latestPrUrl = event.url,
                                latestPrTitle = "#${event.number} ${event.title}"
                            )
                        }
                        is AgentEvent.Completed -> {
                            addLog(LogType.SUCCESS, "Task completed successfully!")
                            _uiState.value = _uiState.value.copy(
                                isRunning = false,
                                latestSummary = event.message
                            )
                        }
                        is AgentEvent.Error -> {
                            addLog(LogType.ERROR, event.message)
                            _uiState.value = _uiState.value.copy(isRunning = false)
                        }
                    }
                }
            } catch (e: Exception) {
                addLog(LogType.ERROR, "Unexpected error: ${e.message}")
            } finally {
                _uiState.value = _uiState.value.copy(isRunning = false)
            }
        }
    }

    fun stopTask() {
        activeJob?.cancel()
        _uiState.value = _uiState.value.copy(isRunning = false)
        addLog(LogType.INFO, "Task cancelled by user.")
    }

    private fun addLog(type: LogType, message: String) {
        val entry = AgentLogItem(
            timestamp = timeFormat.format(Date()),
            type = type,
            message = message
        )
        _uiState.value = _uiState.value.copy(logs = _uiState.value.logs + entry)
    }
}
