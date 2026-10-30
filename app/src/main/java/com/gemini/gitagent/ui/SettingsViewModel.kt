package com.gemini.gitagent.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gemini.gitagent.data.api.GeminiApiClient
import com.gemini.gitagent.data.api.GitHubApiClient
import com.gemini.gitagent.data.repository.AppSettings
import com.gemini.gitagent.data.repository.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val isTesting: Boolean = false,
    val testMessage: String? = null,
    val isTestSuccess: Boolean = false
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = SettingsRepository(application)
    private val _uiState = MutableStateFlow(SettingsUiState(settings = repository.getSettings()))
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

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
        }
    }
}
