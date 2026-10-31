package com.gemini.gitagent.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gemini.gitagent.data.api.GeminiApiClient
import com.gemini.gitagent.data.api.GitHubApiClient
import com.gemini.gitagent.data.repository.AppSettings
import com.gemini.gitagent.data.repository.SettingsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

val DEFAULT_GEMINI_MODELS = listOf(
    "gemini-3.8-flash",
    "gemini-3.8-pro",
    "gemini-3.6-flash",
    "gemini-3.5-flash",
    "gemini-4-argon",
    "gemini-2.5-flash"
)

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val availableModels: List<String> = DEFAULT_GEMINI_MODELS,
    val isFetchingModels: Boolean = false,
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
            _uiState.value = _uiState.value.copy(isFetchingModels = true)
            val client = GeminiApiClient(apiKey = apiKey)
            val result = client.fetchAvailableModels()
            if (result.isSuccess) {
                val models = result.getOrThrow()
                // Merge with default list, placing active fetched models at top
                val combined = (models + DEFAULT_GEMINI_MODELS).distinct()
                _uiState.value = _uiState.value.copy(
                    isFetchingModels = false,
                    availableModels = combined
                )
            } else {
                _uiState.value = _uiState.value.copy(isFetchingModels = false)
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
                isTestSuccess = false
            )
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isTesting = true, testMessage = "Testing connections...")

            val geminiClient = GeminiApiClient(current.geminiApiKey, current.geminiModel)
            val geminiResult = geminiClient.verifyApiKey()

            if (geminiResult.isFailure) {
                _uiState.value = _uiState.value.copy(
                    isTesting = false,
                    isTestSuccess = false,
                    testMessage = "Gemini Connection Failed: ${geminiResult.exceptionOrNull()?.message}"
                )
                // If model failed, attempt to fetch available models
                fetchGeminiModels(current.geminiApiKey)
                return@launch
            }

            val githubClient = GitHubApiClient(current.githubToken, current.githubOwner, current.githubRepo)
            val githubResult = githubClient.verifyRepositoryAccess()

            if (githubResult.isFailure) {
                _uiState.value = _uiState.value.copy(
                    isTesting = false,
                    isTestSuccess = false,
                    testMessage = "GitHub Connection Failed: ${githubResult.exceptionOrNull()?.message}"
                )
                return@launch
            }

            _uiState.value = _uiState.value.copy(
                isTesting = false,
                isTestSuccess = true,
                testMessage = "Success! Both Gemini API and GitHub repository access are verified."
            )
            // Refresh models and repositories on successful test
            fetchGeminiModels(current.geminiApiKey)
            fetchRepositories(current.githubToken, current.githubOwner)
        }
    }
}
