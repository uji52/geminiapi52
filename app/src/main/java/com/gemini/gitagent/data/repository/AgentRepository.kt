package com.gemini.gitagent.data.repository

import com.gemini.gitagent.data.api.GeminiApiClient
import com.gemini.gitagent.data.api.GeminiStepResult
import com.gemini.gitagent.data.api.GitHubApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONArray
import org.json.JSONObject

sealed class AgentEvent {
    data class Status(val message: String) : AgentEvent()
    data class ToolCall(val name: String, val detail: String) : AgentEvent()
    data class ToolResult(val name: String, val summary: String) : AgentEvent()
    data class PullRequestCreated(val url: String, val number: Int, val title: String) : AgentEvent()
    data class Completed(val message: String) : AgentEvent()
    data class Error(val message: String) : AgentEvent()
}

class AgentRepository(
    private val settings: AppSettings
) {
    private val geminiClient = GeminiApiClient(
        apiKey = settings.geminiApiKey,
        model = settings.geminiModel
    )

    private val githubClient = GitHubApiClient(
        token = settings.githubToken,
        owner = settings.githubOwner,
        repo = settings.githubRepo
    )

    fun executeTask(userPrompt: String): Flow<AgentEvent> = flow {
        emit(AgentEvent.Status("Initializing autonomous agent for ${settings.githubOwner}/${settings.githubRepo}..."))

        val systemPrompt = """
            You are an autonomous AI Software Engineer agent capable of modifying code and creating Pull Requests on GitHub.
            The user wants you to perform a task in GitHub repository '${settings.githubOwner}/${settings.githubRepo}'.
            The default base branch is '${settings.githubBaseBranch}'.

            Rules and Guidelines:
            1. Always inspect the codebase using 'list_directory' and 'get_file_content' before making changes.
            2. When modifying or adding code, create a new feature branch first using 'create_branch' (e.g. 'gemini/update-xxx' or 'gemini/fix-yyy').
            3. Apply modifications by calling 'commit_and_push_file' with a clean, descriptive commit message.
            4. Once all necessary file changes are committed, create a Pull Request using 'create_pull_request' into '${settings.githubBaseBranch}'.
            5. Provide a clear markdown explanation in the PR body.
            6. When finished, provide a concise summary to the user including the created Pull Request URL.
        """.trimIndent()

        val contents = JSONArray()

        // Append user prompt
        contents.put(JSONObject().apply {
            put("role", "user")
            put("parts", JSONArray().apply {
                put(JSONObject().put("text", userPrompt))
            })
        })

        var prUrl: String? = null
        var isFinished = false
        var stepCount = 0
        val maxSteps = 15

        while (!isFinished && stepCount < maxSteps) {
            stepCount++
            emit(AgentEvent.Status("Gemini is analyzing... (step $stepCount/$maxSteps)"))

            val stepResult = geminiClient.generateStep(contents, systemPrompt)
            if (stepResult.isFailure) {
                val err = stepResult.exceptionOrNull()?.message ?: "Unknown Gemini API error"
                emit(AgentEvent.Error("Gemini Error: $err"))
                return@flow
            }

            val steps = stepResult.getOrThrow()
            if (steps.isEmpty()) {
                emit(AgentEvent.Error("Empty response from Gemini"))
                return@flow
            }

            // Check if there are function calls
            val functionCalls = steps.filterIsInstance<GeminiStepResult.FunctionCallRequest>()
            val textResponses = steps.filterIsInstance<GeminiStepResult.TextResponse>()

            if (functionCalls.isEmpty()) {
                // Agent finished with text response
                val finalText = textResponses.joinToString("\n") { it.text }
                emit(AgentEvent.Completed(finalText))
                isFinished = true
                break
            }

            // Record model's function calls in contents history
            val modelParts = JSONArray()
            for (fn in functionCalls) {
                modelParts.put(JSONObject().apply {
                    put("functionCall", JSONObject().apply {
                        put("name", fn.functionName)
                        put("args", fn.args)
                    })
                })
            }
            // Add any text the model produced along with function calls
            for (t in textResponses) {
                modelParts.put(JSONObject().put("text", t.text))
            }
            contents.put(JSONObject().apply {
                put("role", "model")
                put("parts", modelParts)
            })

            // Execute each function call and append function responses
            for (fn in functionCalls) {
                val toolName = fn.functionName
                val args = fn.args

                val (toolResultJson, summaryText) = executeTool(toolName, args)

                emit(AgentEvent.ToolResult(toolName, summaryText))

                if (toolName == "create_pull_request" && toolResultJson.optBoolean("success", false)) {
                    val url = toolResultJson.optString("html_url", "")
                    val number = toolResultJson.optInt("number", 0)
                    val title = toolResultJson.optString("title", "")
                    prUrl = url
                    emit(AgentEvent.PullRequestCreated(url, number, title))
                }

                // Add functionResponse to contents
                contents.put(JSONObject().apply {
                    put("role", "function")
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("functionResponse", JSONObject().apply {
                                put("name", toolName)
                                put("response", JSONObject().apply {
                                    put("output", toolResultJson)
                                })
                            })
                        })
                    })
                })
            }
        }

        if (!isFinished) {
            emit(AgentEvent.Completed(
                "Reached maximum step limit ($maxSteps). PR URL: ${prUrl ?: "Not created yet"}."
            ))
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun executeTool(name: String, args: JSONObject): Pair<JSONObject, String> {
        val result = JSONObject()
        return when (name) {
            "list_directory" -> {
                val path = args.optString("path", "")
                val listResult = githubClient.listDirectory(path, settings.githubBaseBranch)
                if (listResult.isSuccess) {
                    val entries = listResult.getOrThrow()
                    val itemsArray = JSONArray()
                    entries.forEach { entry ->
                        itemsArray.put(JSONObject().apply {
                            put("name", entry.name)
                            put("path", entry.path)
                            put("type", entry.type)
                        })
                    }
                    result.put("success", true)
                    result.put("items", itemsArray)
                    Pair(result, "Listed ${entries.size} items in '$path'")
                } else {
                    val err = listResult.exceptionOrNull()?.message ?: "Unknown error"
                    result.put("success", false)
                    result.put("error", err)
                    Pair(result, "Failed to list '$path': $err")
                }
            }
            "get_file_content" -> {
                val path = args.optString("path", "")
                val branch = args.optString("branch", "").ifEmpty { settings.githubBaseBranch }
                val fileResult = githubClient.getFileContent(path, branch)
                if (fileResult.isSuccess) {
                    val file = fileResult.getOrThrow()
                    result.put("success", true)
                    result.put("path", file.path)
                    result.put("content", file.content)
                    result.put("sha", file.sha)
                    Pair(result, "Read file '${file.path}' (${file.content.length} chars)")
                } else {
                    val err = fileResult.exceptionOrNull()?.message ?: "File not found"
                    result.put("success", false)
                    result.put("error", err)
                    Pair(result, "Error reading file '$path': $err")
                }
            }
            "create_branch" -> {
                val newBranch = args.optString("new_branch", "")
                val fromBranch = args.optString("from_branch", "").ifEmpty { settings.githubBaseBranch }
                val branchResult = githubClient.createBranch(newBranch, fromBranch)
                if (branchResult.isSuccess) {
                    val created = branchResult.getOrThrow()
                    result.put("success", true)
                    result.put("branch", created)
                    Pair(result, "Created branch '$created' from '$fromBranch'")
                } else {
                    val err = branchResult.exceptionOrNull()?.message ?: "Error creating branch"
                    result.put("success", false)
                    result.put("error", err)
                    Pair(result, "Branch creation error: $err")
                }
            }
            "commit_and_push_file" -> {
                val path = args.optString("path", "")
                val content = args.optString("content", "")
                val commitMessage = args.optString("commit_message", "Updated by Gemini Git Agent")
                val branch = args.optString("branch", "")
                val commitResult = githubClient.commitAndPushFile(path, content, commitMessage, branch)
                if (commitResult.isSuccess) {
                    val msg = commitResult.getOrThrow()
                    result.put("success", true)
                    result.put("message", msg)
                    Pair(result, "Committed '$path' to '$branch'")
                } else {
                    val err = commitResult.exceptionOrNull()?.message ?: "Error committing"
                    result.put("success", false)
                    result.put("error", err)
                    Pair(result, "Commit failed for '$path': $err")
                }
            }
            "create_pull_request" -> {
                val title = args.optString("title", "Update via Gemini Git Agent")
                val body = args.optString("body", "Autonomous changes committed by Gemini Git Agent.")
                val head = args.optString("head_branch", "")
                val base = args.optString("base_branch", "").ifEmpty { settings.githubBaseBranch }
                val prResult = githubClient.createPullRequest(title, body, head, base)
                if (prResult.isSuccess) {
                    val pr = prResult.getOrThrow()
                    result.put("success", true)
                    result.put("number", pr.number)
                    result.put("html_url", pr.htmlUrl)
                    result.put("title", pr.title)
                    Pair(result, "Pull Request created! #${pr.number}: ${pr.htmlUrl}")
                } else {
                    val err = prResult.exceptionOrNull()?.message ?: "Error creating PR"
                    result.put("success", false)
                    result.put("error", err)
                    Pair(result, "Failed to create PR: $err")
                }
            }
            else -> {
                result.put("success", false)
                result.put("error", "Unknown function: $name")
                Pair(result, "Unknown function called: $name")
            }
        }
    }
}
