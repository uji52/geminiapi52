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

        // Pre-flight check: Verify base branch exists before consuming any Gemini API calls
        val baseCheckResult = githubClient.getBranchHeadSha(settings.githubBaseBranch)
        if (baseCheckResult.isFailure) {
            val err = baseCheckResult.exceptionOrNull()?.message ?: "Base branch check failed"
            emit(AgentEvent.Error("GitHub Setup Error: $err\nPlease check that '${settings.githubBaseBranch}' exists on GitHub and that the repository has at least one commit."))
            return@flow
        }

        val systemPrompt = """
            You are an autonomous AI Software Engineer agent capable of modifying code and creating Pull Requests on GitHub.
            The user wants you to perform a task in GitHub repository '${settings.githubOwner}/${settings.githubRepo}'.
            The default base branch is '${settings.githubBaseBranch}'.

            CRITICAL EFFICIENCY REQUIREMENT (Free Tier / Budget Friendly):
            - The user is on a strict Free Tier quota (max 5 requests). You MUST complete the entire task in as few steps as possible (ideally 1 or 2 steps).
            - Whenever creating or modifying a single file, you MUST use the composite tool 'create_branch_and_commit_pr'. It automatically creates the branch, commits the file, and opens the Pull Request all in ONE step.
            - If the target file path is already known or mentioned by the user (e.g. 'README.md', 'LICENSE', etc.), DO NOT call 'list_directory'.
            - If you are creating a new file or overwriting a file whose structure is known, call 'create_branch_and_commit_pr' immediately on your first turn.
            - If you need to read an existing file first, call 'get_file_content' in step 1, and then call 'create_branch_and_commit_pr' in step 2.
            - Avoid unnecessary exploratory calls. Focus directly on completing the user's objective.

            CRITICAL TOOL EXECUTION RULE:
            - You MUST invoke tools to perform real GitHub actions.
            - NEVER reply with plain text pretending that a PR or commit was created without calling the tool.
            - When asked to create a PR, calling 'create_branch_and_commit_pr' is MANDATORY.
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
        val maxSteps = 4

        while (!isFinished && stepCount < maxSteps) {
            stepCount++
            emit(AgentEvent.Status("Gemini is analyzing... (step $stepCount/$maxSteps)"))

            val stepResult = geminiClient.generateStep(contents, systemPrompt)
            if (stepResult.isFailure) {
                val err = stepResult.exceptionOrNull()?.message ?: "Unknown Gemini API error"
                emit(AgentEvent.Error("Gemini Error: $err"))
                return@flow
            }

            val output = stepResult.getOrThrow()
            val steps = output.results
            if (steps.isEmpty()) {
                emit(AgentEvent.Error("Empty response from Gemini"))
                return@flow
            }

            // Surface any thinking thoughts to status stream
            val thoughts = steps.filterIsInstance<GeminiStepResult.ThoughtResponse>()
            for (th in thoughts) {
                val thoughtPreview = th.thought.trim().replace("\n", " ")
                if (thoughtPreview.isNotBlank()) {
                    val preview = if (thoughtPreview.length > 120) thoughtPreview.take(120) + "..." else thoughtPreview
                    emit(AgentEvent.Status("Thinking: $preview"))
                }
            }

            // Check if there are function calls
            val functionCalls = steps.filterIsInstance<GeminiStepResult.FunctionCallRequest>()
            val textResponses = steps.filterIsInstance<GeminiStepResult.TextResponse>()

            if (functionCalls.isEmpty()) {
                // Agent finished with text response
                val finalText = if (textResponses.isNotEmpty()) {
                    textResponses.joinToString("\n") { it.text }
                } else if (thoughts.isNotEmpty()) {
                    thoughts.joinToString("\n") { it.thought }
                } else {
                    "Task processing complete."
                }
                emit(AgentEvent.Completed(finalText))
                isFinished = true
                break
            }

            // Preserve full model content (including thought parts and signatures) in multi-turn history
            contents.put(output.rawContent)

            // Execute each function call and append function responses
            val responseParts = JSONArray()
            var prCreatedInThisTurn = false

            for (fn in functionCalls) {
                val toolName = fn.functionName
                val args = fn.args

                emit(AgentEvent.ToolCall(toolName, args.toString()))

                val (toolResultJson, summaryText) = executeTool(toolName, args)

                emit(AgentEvent.ToolResult(toolName, summaryText))

                if ((toolName == "create_pull_request" || toolName == "create_branch_and_commit_pr") && toolResultJson.optBoolean("success", false)) {
                    val url = toolResultJson.optString("html_url", "")
                    val number = toolResultJson.optInt("number", 0)
                    val title = toolResultJson.optString("title", "")
                    prUrl = url
                    prCreatedInThisTurn = true
                    emit(AgentEvent.PullRequestCreated(url, number, title))
                }

                // Add functionResponse part
                responseParts.put(JSONObject().apply {
                    put("functionResponse", JSONObject().apply {
                        put("name", toolName)
                        put("response", JSONObject().apply {
                            put("output", toolResultJson)
                        })
                    })
                })
            }

            // If PR was successfully created, finish immediately to save Gemini API requests
            if (prCreatedInThisTurn && prUrl != null) {
                emit(AgentEvent.Completed("Pull Request successfully created: $prUrl"))
                isFinished = true
                break
            }

            // Append user turn containing all functionResponses (Gemini API requires role='user')
            contents.put(JSONObject().apply {
                put("role", "user")
                put("parts", responseParts)
            })
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
            "create_branch_and_commit_pr" -> {
                val branchName = args.optString("branch_name", "").ifEmpty { "gemini/update-${System.currentTimeMillis() / 1000}" }
                val filePath = args.optString("file_path", "")
                val fileContent = args.optString("file_content", "")
                val commitMsg = args.optString("commit_message", "Updated by Gemini Git Agent")
                val prTitle = args.optString("pr_title", "Update via Gemini Git Agent")
                val prBody = args.optString("pr_body", "Autonomous changes committed by Gemini Git Agent.")
                val baseBranch = args.optString("base_branch", "").ifEmpty { settings.githubBaseBranch }

                if (filePath.isBlank()) {
                    result.put("success", false)
                    result.put("error", "file_path is required")
                    return Pair(result, "Failed: file_path is missing")
                }

                // 1. Create feature branch
                val branchResult = githubClient.createBranch(branchName, baseBranch)
                if (branchResult.isFailure) {
                    val err = branchResult.exceptionOrNull()?.message ?: "Failed to create branch"
                    result.put("success", false)
                    result.put("error", err)
                    return Pair(result, "Branch creation error: $err")
                }

                // 2. Commit and push file changes
                val commitResult = githubClient.commitAndPushFile(filePath, fileContent, commitMsg, branchName)
                if (commitResult.isFailure) {
                    val err = commitResult.exceptionOrNull()?.message ?: "Failed to commit changes"
                    result.put("success", false)
                    result.put("error", err)
                    return Pair(result, "Commit failed for '$filePath': $err")
                }

                // 3. Create Pull Request
                val prResult = githubClient.createPullRequest(prTitle, prBody, branchName, baseBranch)
                if (prResult.isFailure) {
                    val err = prResult.exceptionOrNull()?.message ?: "Failed to create PR"
                    result.put("success", false)
                    result.put("error", err)
                    return Pair(result, "PR creation failed: $err")
                }

                val pr = prResult.getOrThrow()
                result.put("success", true)
                result.put("number", pr.number)
                result.put("html_url", pr.htmlUrl)
                result.put("title", pr.title)
                result.put("branch", branchName)
                Pair(result, "All-in-one PR created successfully! #${pr.number}: ${pr.htmlUrl}")
            }
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
            "list_pull_requests" -> {
                val state = args.optString("state", "open")
                val prsResult = githubClient.listPullRequests(state)
                if (prsResult.isSuccess) {
                    val prs = prsResult.getOrThrow()
                    val prsArray = JSONArray()
                    prs.forEach { pr ->
                        prsArray.put(JSONObject().apply {
                            put("number", pr.number)
                            put("html_url", pr.htmlUrl)
                            put("title", pr.title)
                            put("state", pr.state)
                        })
                    }
                    result.put("success", true)
                    result.put("pull_requests", prsArray)
                    Pair(result, "Found ${prs.size} $state pull requests.")
                } else {
                    val err = prsResult.exceptionOrNull()?.message ?: "Error fetching PRs"
                    result.put("success", false)
                    result.put("error", err)
                    Pair(result, "Failed to list PRs: $err")
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
