package com.gemini.gitagent.data.api

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

data class GitHubFileResult(
    val path: String,
    val content: String,
    val sha: String
)

data class GitHubDirectoryEntry(
    val name: String,
    val path: String,
    val type: String // "file" or "dir"
)

data class PullRequestResult(
    val number: Int,
    val htmlUrl: String,
    val title: String,
    val state: String
)

class GitHubApiClient(
    private val token: String,
    private val owner: String = "",
    private val repo: String = ""
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val baseUrl = "https://api.github.com"

    private fun newRequestBuilder(url: String): Request.Builder {
        return Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Accept", "application/vnd.github.v3+json")
            .addHeader("X-GitHub-Api-Version", "2022-11-28")
            .addHeader("User-Agent", "GeminiGitAgent-Android")
    }

    suspend fun fetchRepositories(targetOwner: String = ""): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            if (token.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("GitHub Token is required to list repositories"))
            }
            val effectiveOwner = targetOwner.trim().ifEmpty { owner.trim() }
            val repoList = mutableListOf<String>()

            // 1. Try authenticated user repos (includes private & organization repos user has access to)
            val userReposUrl = "$baseUrl/user/repos?per_page=100&sort=updated&affiliation=owner,collaborator,organization_member"
            val userRequest = newRequestBuilder(userReposUrl).get().build()

            client.newCall(userRequest).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val array = JSONArray(body)
                    for (i in 0 until array.length()) {
                        val item = array.getJSONObject(i)
                        val name = item.getString("name")
                        val ownerLogin = item.optJSONObject("owner")?.optString("login", "") ?: ""
                        if (effectiveOwner.isEmpty() || ownerLogin.equals(effectiveOwner, ignoreCase = true)) {
                            repoList.add(name)
                        }
                    }
                }
            }

            // 2. If filtered list is empty and an owner was specified, try fetching that owner's public/org repos directly
            if (repoList.isEmpty() && effectiveOwner.isNotEmpty()) {
                val ownerUrl = "$baseUrl/users/$effectiveOwner/repos?per_page=100&sort=updated"
                val ownerRequest = newRequestBuilder(ownerUrl).get().build()
                client.newCall(ownerRequest).execute().use { response ->
                    val body = response.body?.string() ?: ""
                    if (response.isSuccessful) {
                        val array = JSONArray(body)
                        for (i in 0 until array.length()) {
                            repoList.add(array.getJSONObject(i).getString("name"))
                        }
                    }
                }
            }

            Result.success(repoList.distinct())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun verifyRepositoryAccess(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/repos/$owner/$repo"
            val request = newRequestBuilder(url).get().build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val json = JSONObject(body)
                    val defaultBranch = json.optString("default_branch", "main")
                    Result.success("Connected to $owner/$repo (default branch: $defaultBranch)")
                } else {
                    Result.failure(IOException("GitHub Error ${response.code}: $body"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun listDirectory(path: String = "", branch: String? = null): Result<List<GitHubDirectoryEntry>> = withContext(Dispatchers.IO) {
        try {
            val cleanPath = path.trim().trimStart('/')
            val urlBuilder = StringBuilder("$baseUrl/repos/$owner/$repo/contents/$cleanPath")
            if (!branch.isNullOrBlank()) {
                urlBuilder.append("?ref=$branch")
            }

            val request = newRequestBuilder(urlBuilder.toString()).get().build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("Failed to list directory: ${response.code} $body"))
                }

                val list = mutableListOf<GitHubDirectoryEntry>()
                val jsonArray = JSONArray(body)
                for (i in 0 until jsonArray.length()) {
                    val item = jsonArray.getJSONObject(i)
                    list.add(
                        GitHubDirectoryEntry(
                            name = item.optString("name"),
                            path = item.optString("path"),
                            type = item.optString("type")
                        )
                    )
                }
                Result.success(list)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getFileContent(path: String, branch: String? = null): Result<GitHubFileResult> = withContext(Dispatchers.IO) {
        try {
            val cleanPath = path.trim().trimStart('/')
            val urlBuilder = StringBuilder("$baseUrl/repos/$owner/$repo/contents/$cleanPath")
            if (!branch.isNullOrBlank()) {
                urlBuilder.append("?ref=$branch")
            }

            val request = newRequestBuilder(urlBuilder.toString()).get().build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("Failed to get file $cleanPath: ${response.code} $body"))
                }

                val json = JSONObject(body)
                val encoding = json.optString("encoding", "base64")
                val rawContent = json.optString("content", "")
                val sha = json.optString("sha", "")

                val decodedText = if (encoding == "base64") {
                    val cleanedBase64 = rawContent.replace("\n", "").replace("\r", "")
                    val bytes = Base64.decode(cleanedBase64, Base64.DEFAULT)
                    String(bytes, StandardCharsets.UTF_8)
                } else {
                    rawContent
                }

                Result.success(GitHubFileResult(path = cleanPath, content = decodedText, sha = sha))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getBranchHeadSha(branch: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val cleanBranch = branch.trim()
            val url = "$baseUrl/repos/$owner/$repo/git/ref/heads/$cleanBranch"
            val request = newRequestBuilder(url).get().build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("Failed to get head SHA for branch '$cleanBranch': ${response.code} $body"))
                }
                val json = JSONObject(body)
                val obj = json.getJSONObject("object")
                val sha = obj.getString("sha")
                Result.success(sha)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun createBranch(newBranch: String, fromBranch: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val cleanNewBranch = newBranch.trim().removePrefix("refs/heads/")
            val fromShaResult = getBranchHeadSha(fromBranch)
            if (fromShaResult.isFailure) {
                return@withContext Result.failure(fromShaResult.exceptionOrNull()!!)
            }
            val fromSha = fromShaResult.getOrThrow()

            val url = "$baseUrl/repos/$owner/$repo/git/refs"
            val payload = JSONObject().apply {
                put("ref", "refs/heads/$cleanNewBranch")
                put("sha", fromSha)
            }

            val request = newRequestBuilder(url)
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (response.isSuccessful || response.code == 201) {
                    Result.success(cleanNewBranch)
                } else if (response.code == 422 && body.contains("Reference already exists")) {
                    // Branch already exists, can reuse
                    Result.success(cleanNewBranch)
                } else {
                    Result.failure(IOException("Failed to create branch '$cleanNewBranch': ${response.code} $body"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun commitAndPushFile(
        path: String,
        content: String,
        commitMessage: String,
        branch: String
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val cleanPath = path.trim().trimStart('/')
            // Check if file already exists to get its sha
            val existingFileResult = getFileContent(cleanPath, branch)
            val currentSha = existingFileResult.getOrNull()?.sha

            val url = "$baseUrl/repos/$owner/$repo/contents/$cleanPath"
            val base64Content = Base64.encodeToString(content.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)

            val payload = JSONObject().apply {
                put("message", commitMessage)
                put("content", base64Content)
                put("branch", branch)
                if (currentSha != null) {
                    put("sha", currentSha)
                }
            }

            val request = newRequestBuilder(url)
                .put(payload.toString().toRequestBody(jsonMediaType))
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (response.isSuccessful || response.code == 200 || response.code == 201) {
                    Result.success("Successfully committed '$cleanPath' to branch '$branch'")
                } else {
                    Result.failure(IOException("Failed to commit file '$cleanPath': ${response.code} $body"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun createPullRequest(
        title: String,
        body: String,
        headBranch: String,
        baseBranch: String
    ): Result<PullRequestResult> = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/repos/$owner/$repo/pulls"
            val payload = JSONObject().apply {
                put("title", title)
                put("body", body)
                put("head", headBranch)
                put("base", baseBranch)
            }

            val request = newRequestBuilder(url)
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string() ?: ""
                if (response.isSuccessful || response.code == 201) {
                    val json = JSONObject(responseBody)
                    val result = PullRequestResult(
                        number = json.getInt("number"),
                        htmlUrl = json.getString("html_url"),
                        title = json.getString("title"),
                        state = json.optString("state", "open")
                    )
                    Result.success(result)
                } else {
                    Result.failure(IOException("Failed to create Pull Request: ${response.code} $responseBody"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun listPullRequests(state: String = "open"): Result<List<PullRequestResult>> = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/repos/$owner/$repo/pulls?state=$state&per_page=30"
            val request = newRequestBuilder(url).get().build()
            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("Failed to list PRs: ${response.code} $responseBody"))
                }
                val array = JSONArray(responseBody)
                val list = mutableListOf<PullRequestResult>()
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    list.add(
                        PullRequestResult(
                            number = item.getInt("number"),
                            htmlUrl = item.getString("html_url"),
                            title = item.getString("title"),
                            state = item.optString("state", "open")
                        )
                    )
                }
                Result.success(list)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
