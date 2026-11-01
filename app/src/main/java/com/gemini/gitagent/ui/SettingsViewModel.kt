package com.gemini.gitagent.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gemini.gitagent.data.api.GeminiApiClient
import com.gemini.gitagent.data.api.GitHubApiClient
import com.gemini.gitagent.data.repository.AppSettings
import com.gemini.gitagent.data.repository.GeminiModelInfo
import com.gemini.gitagent.data.repository.SettingsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

val DEFAULT_GEMINI_MODELS = listOf(
    "gemini-3.5-flash",
    "gemini-3.5-flash-lite",
    "gemini-3.8-flash",
    "gemini-flash-latest",
    "gemini-3.8-pro",
    "gemini-4-argon"
)

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val availableModels: List<String> = DEFAULT_GEMINI_MODELS,
    val availableModelInfos: List<GeminiModelInfo> = DEFAULT_GEMINI_MODELS.map { GeminiModelInfo.fromModelId(it) },
    val isFetchingModels: Boolean = false,
    val fetchModelsError: String? = null,
    val availableRepositories: List<String> = emptyList(),
    val isFetchingRepos: Boolean = false,
    val fetchReposError: String? = null,
    val isTesting: Boolean = false,
    val testMessage: String? = null,
    val isTestSuccess: Boolean = false
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = SettingsRepository(application)
    private val _uiState = MutableStateFlow(SettingsUiState(settings = repository.getSettings()))
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private var fetchReposJob: Job? = null
    private var fetchModelsJob: Job? = null

    init {
        val initialSettings = _uiState.value.settings
        if (initialSettings.geminiApiKey.isNotBlank()) {
            fetchGeminiModels(initialSettings.geminiApiKey)
        }
        if (initialSettings.githubToken.isNotBlank()) {
            fetchRepositories(initialSettings.githubToken, initialSettings.githubOwner)
        }
    }

    fun fetchGeminiModels(apiKey: String, debounceMs: Long = 0) {
        if (apiKey.isBlank()) return
        fetchModelsJob?.cancel()
        fetchModelsJob = viewModelScope.launch {
            if (debounceMs > 0) {
                delay(debounceMs)
            }
            _uiState.value = _uiState.value.copy(isFetchingModels = true, fetchModelsError = null)
            val client = GeminiApiClient(apiKey = apiKey)
            val result = client.fetchAvailableModels()
            if (result.isSuccess) {
                val models = result.getOrThrow()
                val rawList = if (models.isNotEmpty()) models else DEFAULT_GEMINI_MODELS
                val modelInfos = rawList.map { GeminiModelInfo.fromModelId(it) }
                    .sortedWith(
                        compareByDescending<GeminiModelInfo> { it.isRecommended }
                            .thenByDescending { it.isFreeTier }
                            .thenByDescending { it.id.contains("3.5") }
                            .thenByDescending { it.id.contains("3.8") }
                            .thenBy { it.id }
                    )
                _uiState.value = _uiState.value.copy(
                    isFetchingModels = false,
                    availableModels = modelInfos.map { it.id },
                    availableModelInfos = modelInfos
                )
            } else {
                val err = result.exceptionOrNull()?.message ?: "Unknown error"
                _uiState.value = _uiState.value.copy(
                    isFetchingModels = false,
                    fetchModelsError = err,
                    testMessage = "Models fetch: $err",
                    isTestSuccess = false
                )
            }
        }
    }

    fun fetchRepositories(token: String, owner: String, debounceMs: Long = 0) {
        if (token.isBlank()) return
        fetchReposJob?.cancel()
        fetchReposJob = viewModelScope.launch {
            if (debounceMs > 0) {
                delay(debounceMs)
            }
            _uiState.value = _uiState.value.copy(isFetchingRepos = true, fetchReposError = null)
            val client = GitHubApiClient(token = token, owner = owner)
            val result = client.fetchRepositories(owner)
            if (result.isSuccess) {
                val repos = result.getOrThrow()
                _uiState.value = _uiState.value.copy(
                    isFetchingRepos = false,
                    availableRepositories = repos
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    isFetchingRepos = false,
                    fetchReposError = result.exceptionOrNull()?.message
                )
            }
        }
    }

    fun updateSettings(
        geminiApiKey: String,
        geminiModel: String,
        githubToken: String,
        githubOwner: String,
        githubRepo: String,
        githubBaseBranch: String
    ) {
        val updated = AppSettings(
            geminiApiKey = geminiApiKey,
            geminiModel = geminiModel,
            githubToken = githubToken,
            githubOwner = githubOwner,
            githubRepo = githubRepo,
            githubBaseBranch = githubBaseBranch
        )
        repository.saveSettings(updated)
        _uiState.value = _uiState.value.copy(settings = updated, testMessage = null)
    }

    fun testConnection() {
        val current = _uiState.value.settings
        if (!current.isConfigured) {
            _uiState.value = _uiState.value.copy(
                testMessage = "Please fill in all required fields (Gemini API Key, GitHub Token, Owner, Repo)",
                isTestSuccess = false,
                isTesting = false
            )
            return
        }

        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isTesting = true, testMessage = "Testing connections...")

                val geminiClient = GeminiApiClient(current.geminiApiKey, current.geminiModel)
                val geminiResult = geminiClient.verifyApiKey()

                if (geminiResult.isFailure) {
                    val errMsg = geminiResult.exceptionOrNull()?.message ?: "Unknown error"
                    _uiState.value = _uiState.value.copy(
                        isTesting = false,
                        isTestSuccess = false,
                        testMessage = "Gemini Connection Failed: $errMsg"
                    )
                    // If model failed, attempt to fetch available models
                    fetchGeminiModels(current.geminiApiKey)
                    return@launch
                }

                val githubClient = GitHubApiClient(current.githubToken, current.githubOwner, current.githubRepo)
                val githubResult = githubClient.verifyRepositoryAccess()

                if (githubResult.isFailure) {
                    val errMsg = githubResult.exceptionOrNull()?.message ?: "Unknown error"
                    _uiState.value = _uiState.value.copy(
                        isTesting = false,
                        isTestSuccess = false,
                        testMessage = "GitHub Connection Failed: $errMsg"
                    )
                    return@launch
                }

                _uiState.value = _uiState.value.copy(
                    isTesting = false,
                    isTestSuccess = true,
                    testMessage = "Success! Both Gemini API and GitHub repository access are verified."
                )
                fetchGeminiModels(current.geminiApiKey)
                fetchRepositories(current.githubToken, current.githubOwner)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isTesting = false,
                    isTestSuccess = false,
                    testMessage = "Connection Test Error: ${e.message}"
                )
            } finally {
                // Ensure isTesting is ALWAYS turned off
                if (_uiState.value.isTesting) {
                    _uiState.value = _uiState.value.copy(isTesting = false)
                }
            }
        }
    }
}
