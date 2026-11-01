package com.gemini.gitagent.data.api

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

sealed class GeminiStepResult {
    data class TextResponse(val text: String) : GeminiStepResult()
    data class ThoughtResponse(val thought: String) : GeminiStepResult()
    data class FunctionCallRequest(
        val functionName: String,
        val args: JSONObject
    ) : GeminiStepResult()
}

data class GeminiStepOutput(
    val results: List<GeminiStepResult>,
    val rawContent: JSONObject
)

class GeminiApiClient(
    private val apiKey: String,
    private val model: String = "gemini-3.8-flash"
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(150, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun fetchAvailableModels(): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            if (apiKey.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("API Key is required to list models"))
            }
            val url = "https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey"
            val request = Request.Builder()
                .url(url)
                .addHeader("x-goog-api-key", apiKey)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    val msg = parseErrorMessage(response.code, body)
                    return@withContext Result.failure(IOException(msg))
                }
                val json = JSONObject(body)
                val modelsArray = json.optJSONArray("models") ?: JSONArray()
                val list = mutableListOf<String>()
                for (i in 0 until modelsArray.length()) {
                    val m = modelsArray.getJSONObject(i)
                    val supportedMethods = m.optJSONArray("supportedGenerationMethods") ?: JSONArray()
                    var supportsGenerateContent = false
                    for (j in 0 until supportedMethods.length()) {
                        if (supportedMethods.getString(j) == "generateContent") {
                            supportsGenerateContent = true
                            break
                        }
                    }
                    if (supportsGenerateContent) {
                        val rawName = m.getString("name") // e.g. "models/gemini-3.8-flash"
                        list.add(rawName.removePrefix("models/"))
                    }
                }
                // Sort with preference for latest flash/pro models
                val sorted = list.sortedWith(
                    compareByDescending<String> { it.contains("3.8") }
                        .thenByDescending { it.contains("3.5") }
                        .thenByDescending { it.contains("flash") }
                        .thenBy { it }
                )
                Result.success(sorted)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun verifyApiKey(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val cleanModel = model.trim().removePrefix("models/")
            Log.d("GeminiApi", "Verifying model: $cleanModel")

            // Step 1: Lightweight GET check for model existence & API key validation
            val getUrl = "https://generativelanguage.googleapis.com/v1beta/models/$cleanModel"
            val getReq = Request.Builder()
                .url(getUrl)
                .addHeader("x-goog-api-key", apiKey)
                .get()
                .build()

            client.newCall(getReq).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                Log.d("GeminiApi", "GET model response: code=${resp.code}")
                if (!resp.isSuccessful) {
                    val errorMsg = parseErrorMessage(resp.code, body, cleanModel)
                    return@withContext Result.failure(IOException(errorMsg))
                }
            }

            // Step 2: Test real generateContent with a minimal prompt to measure latency
            val startTime = System.currentTimeMillis()
            val postUrl = "https://generativelanguage.googleapis.com/v1beta/models/$cleanModel:generateContent"
            val testBody = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().put("text", "Hello"))
                        })
                    })
                })
            }

            val postReq = Request.Builder()
                .url(postUrl)
                .addHeader("x-goog-api-key", apiKey)
                .post(testBody.toString().toRequestBody(jsonMediaType))
                .build()

            Log.d("GeminiApi", "Testing POST generateContent to $postUrl")
            client.newCall(postReq).execute().use { resp ->
                val elapsed = System.currentTimeMillis() - startTime
                val body = resp.body?.string() ?: ""
                Log.d("GeminiApi", "POST generateContent result: code=${resp.code}, time=${elapsed}ms")
                if (!resp.isSuccessful) {
                    val errorMsg = parseErrorMessage(resp.code, body, cleanModel)
                    return@withContext Result.failure(IOException("generateContent test failed: $errorMsg"))
                }
                Result.success("Gemini API connection OK! Model '$cleanModel' responded in ${elapsed}ms.")
            }
        } catch (e: Exception) {
            Log.e("GeminiApi", "verifyApiKey exception", e)
            Result.failure(e)
        }
    }

    private fun parseErrorMessage(code: Int, body: String, targetModel: String = ""): String {
        try {
            val json = JSONObject(body)
            val errorObj = json.optJSONObject("error")
            val message = errorObj?.optString("message")
            if (!message.isNullOrBlank()) {
                return "($code) $message"
            }
        } catch (_: Exception) {}

        return when (code) {
            400 -> "(400) Invalid request or API key."
            402 -> "(402) Payment Required. Your prepayment credits are depleted. Please top up in Google AI Studio or use a free tier project."
            403 -> "(403) Forbidden. API key is invalid or has insufficient permissions."
            404 -> if (targetModel.isNotEmpty()) "(404) Model '$targetModel' is no longer available. Tap 'Fetch Models' to pick an active model." else "(404) Resource not found."
            429 -> "(429) Too Many Requests / Rate limit exceeded."
            else -> "Gemini API Error $code: $body"
        }
    }

    suspend fun generateStep(
        contents: JSONArray,
        systemPrompt: String
    ): Result<GeminiStepOutput> = withContext(Dispatchers.IO) {
        try {
            val cleanModel = model.trim().removePrefix("models/")
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$cleanModel:generateContent?key=$apiKey"

            // Build request with thinkingLevel = "low" to minimize thinking delay for fast agent execution
            val rootJson = JSONObject().apply {
                // System Instruction
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().put("text", systemPrompt))
                    })
                })

                // Contents history
                put("contents", contents)

                // Tools declaration
                put("tools", JSONArray().apply {
                    put(JSONObject().apply {
                        put("functionDeclarations", buildFunctionDeclarations())
                    })
                })

                // Set thinkingLevel to 'low' for rapid reasoning and response
                put("generationConfig", JSONObject().apply {
                    put("thinkingConfig", JSONObject().apply {
                        put("thinkingLevel", "low")
                    })
                })
            }

            var request = Request.Builder()
                .url(url)
                .addHeader("x-goog-api-key", apiKey)
                .post(rootJson.toString().toRequestBody(jsonMediaType))
                .build()

            Log.d("GeminiApi", "generateStep sending to $url with body: ${rootJson.toString().take(200)}...")
            val reqStart = System.currentTimeMillis()
            var response = client.newCall(request).execute()
            var body = response.body?.string() ?: ""
            Log.d("GeminiApi", "generateStep responded: code=${response.code}, time=${System.currentTimeMillis() - reqStart}ms")

            // Fallback: If thinkingConfig isn't accepted by this specific model (HTTP 400), retry without thinkingConfig
            if (!response.isSuccessful && response.code == 400 && rootJson.has("generationConfig")) {
                Log.w("GeminiApi", "Retrying generateStep without generationConfig due to HTTP 400")
                response.close()
                rootJson.remove("generationConfig")
                request = Request.Builder()
                    .url(url)
                    .addHeader("x-goog-api-key", apiKey)
                    .post(rootJson.toString().toRequestBody(jsonMediaType))
                    .build()
                val retryStart = System.currentTimeMillis()
                response = client.newCall(request).execute()
                body = response.body?.string() ?: ""
                Log.d("GeminiApi", "generateStep retry responded: code=${response.code}, time=${System.currentTimeMillis() - retryStart}ms")
            }

            response.use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(IOException(parseErrorMessage(resp.code, body, cleanModel)))
                }

                val responseJson = JSONObject(body)
                val candidates = responseJson.optJSONArray("candidates")
                if (candidates == null || candidates.length() == 0) {
                    return@withContext Result.failure(IOException("No response candidates from Gemini API"))
                }

                val candidate = candidates.getJSONObject(0)
                val content = candidate.optJSONObject("content")
                    ?: return@withContext Result.failure(IOException("Empty content returned from Gemini API"))
                val parts = content.optJSONArray("parts") ?: JSONArray()

                val results = mutableListOf<GeminiStepResult>()
                for (i in 0 until parts.length()) {
                    val part = parts.getJSONObject(i)
                    val isThought = part.optBoolean("thought", false)

                    if (part.has("functionCall")) {
                        val fnCall = part.getJSONObject("functionCall")
                        val fnName = fnCall.getString("name")
                        val fnArgs = fnCall.optJSONObject("args") ?: JSONObject()
                        results.add(GeminiStepResult.FunctionCallRequest(fnName, fnArgs))
                    } else if (part.has("text")) {
                        val text = part.getString("text")
                        if (text.isNotBlank()) {
                            if (isThought) {
                                results.add(GeminiStepResult.ThoughtResponse(text))
                            } else {
                                results.add(GeminiStepResult.TextResponse(text))
                            }
                        }
                    }
                }

                Result.success(GeminiStepOutput(results = results, rawContent = content))
            }
        } catch (e: Exception) {
            val friendlyError = when (e) {
                is java.net.SocketTimeoutException ->
                    IOException("Request to Gemini API timed out. The model took too long to respond. (${e.message})", e)
                else -> e
            }
            Result.failure(friendlyError)
        }
    }

    private fun buildFunctionDeclarations(): JSONArray {
        val array = JSONArray()

        // 0. create_branch_and_commit_pr (All-in-one tool to minimize API calls)
        array.put(JSONObject().apply {
            put("name", "create_branch_and_commit_pr")
            put("description", "Recommended all-in-one tool. Creates a new branch, commits the file changes, and opens a Pull Request in a single operation. Use this to complete tasks in the fewest possible steps.")
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("branch_name", JSONObject().apply {
                        put("type", "string")
                        put("description", "New branch name (e.g. 'gemini/update-readme')")
                    })
                    put("file_path", JSONObject().apply {
                        put("type", "string")
                        put("description", "Target file path to create or modify (e.g. 'README.md')")
                    })
                    put("file_content", JSONObject().apply {
                        put("type", "string")
                        put("description", "Full updated text content of the file")
                    })
                    put("commit_message", JSONObject().apply {
                        put("type", "string")
                        put("description", "Git commit message")
                    })
                    put("pr_title", JSONObject().apply {
                        put("type", "string")
                        put("description", "Title of the Pull Request")
                    })
                    put("pr_body", JSONObject().apply {
                        put("type", "string")
                        put("description", "Markdown description for the Pull Request")
                    })
                    put("base_branch", JSONObject().apply {
                        put("type", "string")
                        put("description", "Optional base branch (defaults to repository default branch)")
                    })
                })
                put("required", JSONArray().apply {
                    put("branch_name")
                    put("file_path")
                    put("file_content")
                    put("commit_message")
                    put("pr_title")
                })
            })
        })

        // 1. list_directory
        array.put(JSONObject().apply {
            put("name", "list_directory")
            put("description", "List files and subdirectories at a given path in the repository.")
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("path", JSONObject().apply {
                        put("type", "string")
                        put("description", "Directory path inside the repository, e.g. '' for root or 'app/src'")
                    })
                })
            })
        })

        // 2. get_file_content
        array.put(JSONObject().apply {
            put("name", "get_file_content")
            put("description", "Fetch the text content of a file from the repository.")
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("path", JSONObject().apply {
                        put("type", "string")
                        put("description", "The file path in the repository (e.g. 'README.md')")
                    })
                    put("branch", JSONObject().apply {
                        put("type", "string")
                        put("description", "Optional branch name to read from. Omit to read from default branch.")
                    })
                })
                put("required", JSONArray().apply { put("path") })
            })
        })

        // 3. create_branch
        array.put(JSONObject().apply {
            put("name", "create_branch")
            put("description", "Create a new Git branch from an existing base branch.")
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("new_branch", JSONObject().apply {
                        put("type", "string")
                        put("description", "Name of the new branch (e.g. 'gemini/update-xxx' or 'feature/add-login')")
                    })
                    put("from_branch", JSONObject().apply {
                        put("type", "string")
                        put("description", "The base branch to branch off of (e.g. 'main' or 'master')")
                    })
                })
                put("required", JSONArray().apply {
                    put("new_branch")
                    put("from_branch")
                })
            })
        })

        // 4. commit_and_push_file
        array.put(JSONObject().apply {
            put("name", "commit_and_push_file")
            put("description", "Create or update a file and commit it directly to the specified branch in GitHub.")
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("path", JSONObject().apply {
                        put("type", "string")
                        put("description", "File path in repository to create or modify")
                    })
                    put("content", JSONObject().apply {
                        put("type", "string")
                        put("description", "Full updated text content of the file")
                    })
                    put("commit_message", JSONObject().apply {
                        put("type", "string")
                        put("description", "Clear git commit message describing the modification")
                    })
                    put("branch", JSONObject().apply {
                        put("type", "string")
                        put("description", "Branch name where the commit should be saved")
                    })
                })
                put("required", JSONArray().apply {
                    put("path")
                    put("content")
                    put("commit_message")
                    put("branch")
                })
            })
        })

        // 5. create_pull_request
        array.put(JSONObject().apply {
            put("name", "create_pull_request")
            put("description", "Open a GitHub Pull Request from the feature branch into the base branch.")
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("title", JSONObject().apply {
                        put("type", "string")
                        put("description", "Title of the Pull Request")
                    })
                    put("body", JSONObject().apply {
                        put("type", "string")
                        put("description", "Markdown description explaining the changes in the Pull Request")
                    })
                    put("head_branch", JSONObject().apply {
                        put("type", "string")
                        put("description", "The feature branch containing the new commits")
                    })
                    put("base_branch", JSONObject().apply {
                        put("type", "string")
                        put("description", "The target base branch to merge into (e.g. 'main')")
                    })
                })
                put("required", JSONArray().apply {
                    put("title")
                    put("body")
                    put("head_branch")
                    put("base_branch")
                })
            })
        })

        // 6. list_pull_requests
        array.put(JSONObject().apply {
            put("name", "list_pull_requests")
            put("description", "List pull requests in the repository to inspect open PRs.")
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("state", JSONObject().apply {
                        put("type", "string")
                        put("description", "State of PRs to fetch: 'open', 'closed', or 'all'. Defaults to 'open'.")
                    })
                })
            })
        })

        return array
    }
}
