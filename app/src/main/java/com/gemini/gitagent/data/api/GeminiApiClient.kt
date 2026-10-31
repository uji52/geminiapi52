package com.gemini.gitagent.data.api

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
    data class FunctionCallRequest(
        val functionName: String,
        val args: JSONObject
    ) : GeminiStepResult()
}

class GeminiApiClient(
    private val apiKey: String,
    private val model: String = "gemini-3.8-flash"
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
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
                    return@withContext Result.failure(IOException("Failed to fetch models (${response.code}): $body"))
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
                        .thenByDescending { it.contains("3.6") }
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
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$cleanModel:generateContent?key=$apiKey"
            val payload = JSONObject().apply {
                val contents = JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("parts", JSONArray().apply {
                            put(JSONObject().put("text", "Ping"))
                        })
                    })
                }
                put("contents", contents)
            }

            val request = Request.Builder()
                .url(url)
                .addHeader("x-goog-api-key", apiKey)
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    Result.success("Gemini API connection OK (Model: $cleanModel)")
                } else {
                    val errorMsg = if (response.code == 404) {
                        "Model '$cleanModel' is no longer available (404). Please tap 'Fetch Models' or select an active model from the dropdown."
                    } else {
                        "Gemini API Error ${response.code}: $body"
                    }
                    Result.failure(IOException(errorMsg))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun generateStep(
        contents: JSONArray,
        systemPrompt: String
    ): Result<List<GeminiStepResult>> = withContext(Dispatchers.IO) {
        try {
            val cleanModel = model.trim().removePrefix("models/")
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$cleanModel:generateContent?key=$apiKey"

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
            }

            val request = Request.Builder()
                .url(url)
                .post(rootJson.toString().toRequestBody(jsonMediaType))
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("Gemini API error ${response.code}: $body"))
                }

                val responseJson = JSONObject(body)
                val candidates = responseJson.optJSONArray("candidates")
                if (candidates == null || candidates.length() == 0) {
                    return@withContext Result.failure(IOException("No response candidates from Gemini API"))
                }

                val candidate = candidates.getJSONObject(0)
                val content = candidate.optJSONObject("content")
                val parts = content?.optJSONArray("parts") ?: JSONArray()

                val results = mutableListOf<GeminiStepResult>()
                for (i in 0 until parts.length()) {
                    val part = parts.getJSONObject(i)
                    if (part.has("functionCall")) {
                        val fnCall = part.getJSONObject("functionCall")
                        val fnName = fnCall.getString("name")
                        val fnArgs = fnCall.optJSONObject("args") ?: JSONObject()
                        results.add(GeminiStepResult.FunctionCallRequest(fnName, fnArgs))
                    } else if (part.has("text")) {
                        val text = part.getString("text")
                        if (text.isNotBlank()) {
                            results.add(GeminiStepResult.TextResponse(text))
                        }
                    }
                }

                Result.success(results)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun buildFunctionDeclarations(): JSONArray {
        val array = JSONArray()

        // 1. list_directory
        array.put(JSONObject().apply {
            put("name", "list_directory")
            put("description", "List files and subdirectories at a given path in the repository.")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("path", JSONObject().apply {
                        put("type", "STRING")
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
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("path", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "The file path in the repository (e.g. 'README.md')")
                    })
                    put("branch", JSONObject().apply {
                        put("type", "STRING")
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
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("new_branch", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "Name of the new branch (e.g. 'gemini/fix-readme' or 'feature/add-login')")
                    })
                    put("from_branch", JSONObject().apply {
                        put("type", "STRING")
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
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("path", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "File path in repository to create or modify")
                    })
                    put("content", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "Full updated text content of the file")
                    })
                    put("commit_message", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "Clear git commit message describing the modification")
                    })
                    put("branch", JSONObject().apply {
                        put("type", "STRING")
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
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("title", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "Title of the Pull Request")
                    })
                    put("body", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "Markdown description explaining the changes in the Pull Request")
                    })
                    put("head_branch", JSONObject().apply {
                        put("type", "STRING")
                        put("description", "The feature branch containing the new commits")
                    })
                    put("base_branch", JSONObject().apply {
                        put("type", "STRING")
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

        return array
    }
}
