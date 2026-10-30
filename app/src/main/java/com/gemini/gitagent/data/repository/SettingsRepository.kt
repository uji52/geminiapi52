package com.gemini.gitagent.data.repository

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

data class AppSettings(
    val geminiApiKey: String = "",
    val geminiModel: String = "gemini-2.5-flash",
    val githubToken: String = "",
    val githubOwner: String = "",
    val githubRepo: String = "",
    val githubBaseBranch: String = "main"
) {
    val isConfigured: Boolean
        get() = geminiApiKey.isNotBlank() &&
                githubToken.isNotBlank() &&
                githubOwner.isNotBlank() &&
                githubRepo.isNotBlank()
}

class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            "secret_agent_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        // Fallback for custom emulators/test environments where KeyStore might throw
        context.getSharedPreferences("agent_prefs_fallback", Context.MODE_PRIVATE)
    }

    companion object {
        private const val KEY_GEMINI_API_KEY = "gemini_api_key"
        private const val KEY_GEMINI_MODEL = "gemini_model"
        private const val KEY_GITHUB_TOKEN = "github_token"
        private const val KEY_GITHUB_OWNER = "github_owner"
        private const val KEY_GITHUB_REPO = "github_repo"
        private const val KEY_GITHUB_BASE_BRANCH = "github_base_branch"
        const val DEFAULT_MODEL = "gemini-2.5-flash"
        const val DEFAULT_BRANCH = "main"
    }

    fun getSettings(): AppSettings {
        return AppSettings(
            geminiApiKey = prefs.getString(KEY_GEMINI_API_KEY, "") ?: "",
            geminiModel = prefs.getString(KEY_GEMINI_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL,
            githubToken = prefs.getString(KEY_GITHUB_TOKEN, "") ?: "",
            githubOwner = prefs.getString(KEY_GITHUB_OWNER, "") ?: "",
            githubRepo = prefs.getString(KEY_GITHUB_REPO, "") ?: "",
            githubBaseBranch = prefs.getString(KEY_GITHUB_BASE_BRANCH, DEFAULT_BRANCH) ?: DEFAULT_BRANCH
        )
    }

    fun saveSettings(settings: AppSettings) {
        prefs.edit()
            .putString(KEY_GEMINI_API_KEY, settings.geminiApiKey.trim())
            .putString(KEY_GEMINI_MODEL, settings.geminiModel.trim().ifEmpty { DEFAULT_MODEL })
            .putString(KEY_GITHUB_TOKEN, settings.githubToken.trim())
            .putString(KEY_GITHUB_OWNER, settings.githubOwner.trim())
            .putString(KEY_GITHUB_REPO, settings.githubRepo.trim())
            .putString(KEY_GITHUB_BASE_BRANCH, settings.githubBaseBranch.trim().ifEmpty { DEFAULT_BRANCH })
            .apply()
    }
}
