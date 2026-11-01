package com.gemini.gitagent.data.repository

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

data class AppSettings(
    val geminiApiKey: String = "",
    val geminiModel: String = "gemini-3.5-flash",
    val githubToken: String = "",
    val githubOwner: String = "",
    val githubRepo: String = "",
    val githubBaseBranch: String = "master"
) {
    val isConfigured: Boolean
        get() = geminiApiKey.isNotBlank() &&
                githubToken.isNotBlank() &&
                githubOwner.isNotBlank() &&
                githubRepo.isNotBlank()
}

data class GeminiModelInfo(
    val id: String,
    val displayName: String,
    val isFreeTier: Boolean,
    val tierLabel: String,
    val description: String,
    val isRecommended: Boolean = false
) {
    companion object {
        fun fromModelId(modelId: String): GeminiModelInfo {
            val clean = modelId.trim().removePrefix("models/")
            val isTts = clean.contains("tts", ignoreCase = true)
            val isFlash = clean.contains("flash", ignoreCase = true)
            val isPro = clean.contains("pro", ignoreCase = true)
            val isArgon = clean.contains("argon", ignoreCase = true)

            val isFree = isFlash && !isTts
            val isRec = clean == "gemini-3.5-flash"

            val tierLabel = when {
                isTts -> "音声特化"
                isFree -> if (isRec) "無料枠 (推奨)" else "無料枠"
                isPro || isArgon -> "有料 (要クレジット)"
                else -> "要確認"
            }

            val description = when (clean) {
                "gemini-3.5-flash" -> "★推奨: 高速・安定・無料枠対応。エージェント検証済み。"
                "gemini-3.5-flash-lite" -> "超軽量・高速応答・無料枠対応。"
                "gemini-3.8-flash" -> "高度推論対応・無料枠対応（※時間帯により混雑・高負荷の場合あり）。"
                "gemini-flash-latest" -> "最新Flashモデル自動参照・無料枠対応。"
                "gemini-3.8-pro" -> "高度推論Proモデル。無料枠対象外（要クレジット残高）。"
                "gemini-4-argon" -> "最上位フラッグシップ。有料専用（要クレジット残高）。"
                else -> when {
                    isTts -> "音声生成専用モデル（コード編集・エージェントには不向き）"
                    isFree -> "Flash系モデル。無料枠（Free Tier）で利用可能"
                    else -> "有料課金または事前購入クレジットが必要なモデル"
                }
            }

            return GeminiModelInfo(
                id = clean,
                displayName = clean,
                isFreeTier = isFree,
                tierLabel = tierLabel,
                description = description,
                isRecommended = isRec
            )
        }
    }
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
        const val DEFAULT_MODEL = "gemini-3.5-flash"
        const val DEFAULT_BRANCH = "master"
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
