package com.utbildning.orchestrator

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

class CodexRunner(
    private val options: CliOptions,
) {
    private val statusRegex = Regex("""\"status\"\s*:\s*\"(completed|partial|blocked|failed)\"""")
    private val summaryRegex = Regex("""\"summary\"\s*:\s*\"((?:\\.|[^\"\\])*)\"""")
    private val commitMessageRegex = Regex("""\"commit_message\"\s*:\s*\"((?:\\.|[^\"\\])*)\"""")
    private val threadRegex = Regex("""\"thread_id\"\s*:\s*\"([^\"]+)\"""")

    fun run(task: PlanTask): RunResult {
        val stateDir = options.projectRoot.resolve(".codex-orchestrator")
        val logDir = stateDir.resolve("runs")
        Files.createDirectories(logDir)
        val safeTimestamp = Instant.now().toString().replace(':', '-')
        val logFile = logDir.resolve("$safeTimestamp-${task.id}.jsonl")
        val resultFile = Files.createTempFile(stateDir, "${task.id}-result-", ".json")
        val schemaFile = Files.createTempFile(stateDir, "result-schema-", ".json")
        Files.writeString(schemaFile, OUTPUT_SCHEMA, StandardCharsets.UTF_8)

        val command = buildCommand(task, schemaFile, resultFile)

        var threadId: String? = null
        val process = ProcessBuilder(command)
            .directory(options.projectRoot.toFile())
            .redirectErrorStream(true)
            .start()

        Files.newBufferedWriter(logFile, StandardCharsets.UTF_8).use { writer ->
            process.inputStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    writer.appendLine(line)
                    writer.flush()
                    if (!line.trimStart().startsWith('{')) System.err.println(line)
                    threadRegex.find(line)?.groupValues?.get(1)?.let { threadId = it }
                }
            }
        }
        val exitCode = process.waitFor()
        val finalMessage = if (Files.isRegularFile(resultFile)) Files.readString(resultFile) else ""
        Files.deleteIfExists(resultFile)
        Files.deleteIfExists(schemaFile)

        return RunResult(
            taskId = task.id,
            exitCode = exitCode,
            status = parseStatus(finalMessage),
            summary = parseSummary(finalMessage),
            commitMessage = parseJsonString(commitMessageRegex, finalMessage),
            threadId = threadId,
            logFile = logFile,
        )
    }

    internal fun buildCommand(task: PlanTask, schemaFile: Path, resultFile: Path): List<String> =
        buildList {
            add(options.codexCommand)
            add("exec")
            if (options.approveForMe) {
                add("--approve-for-me")
            } else {
                add("--sandbox")
                add("workspace-write")
            }
            add("--json")
            add("-C")
            add(options.projectRoot.toString())
            if (options.managedWorktree) add("--worktree")
            options.model?.let {
                add("--model")
                add(it)
            }
            add("--output-schema")
            add(schemaFile.toString())
            add("--output-last-message")
            add(resultFile.toString())
            add(buildPrompt(task))
        }

    private fun buildPrompt(task: PlanTask): String = """
        Work on exactly one task: ${task.id} — ${task.title}

        Project root: ${options.projectRoot}
        Plan: ${options.projectRoot.relativize(options.planPath)}
        Task definition:
        ${task.body}

        Read and follow the project's AGENTS.md and only the documentation relevant to this task.
        Complete the task, run checks proportionate to its acceptance criteria, and update the plan
        and durable project status as required by the project instructions. Do not start another task.
        Do not commit, push, create a pull request, publish, or merge. The orchestrator creates the
        commit after validating your result. Preserve unrelated changes.

        Return status "completed" only when the task's acceptance criteria are satisfied and its
        checkbox is marked complete in the plan. Otherwise return "partial", "blocked", or "failed"
        with a concise summary. For a completed task, propose a concise English imperative Git commit
        subject in commit_message; otherwise return an empty commit_message. Your final response must
        match the supplied JSON schema.
    """.trimIndent()

    private fun parseStatus(message: String): RunStatus = when (
        statusRegex.find(message)?.groupValues?.get(1)
    ) {
        "completed" -> RunStatus.COMPLETED
        "partial" -> RunStatus.PARTIAL
        "blocked" -> RunStatus.BLOCKED
        "failed" -> RunStatus.FAILED
        else -> RunStatus.UNKNOWN
    }

    private fun parseSummary(message: String): String = parseJsonString(summaryRegex, message)

    private fun parseJsonString(regex: Regex, message: String): String = regex.find(message)
        ?.groupValues
        ?.get(1)
        ?.replace("\\n", "\n")
        ?.replace("\\\"", "\"")
        ?.replace("\\\\", "\\")
        .orEmpty()

    private companion object {
        val OUTPUT_SCHEMA = """
            {
              "type": "object",
              "additionalProperties": false,
              "properties": {
                "status": {
                  "type": "string",
                  "enum": ["completed", "partial", "blocked", "failed"]
                },
                "summary": { "type": "string" },
                "commit_message": { "type": "string" }
              },
              "required": ["status", "summary", "commit_message"]
            }
        """.trimIndent()
    }
}
