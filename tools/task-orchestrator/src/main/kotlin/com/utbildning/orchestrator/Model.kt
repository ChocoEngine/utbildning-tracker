package com.utbildning.orchestrator

import java.nio.file.Path

data class PlanTask(
    val id: String,
    val title: String,
    val body: String,
    val completed: Boolean,
    val dependencies: Set<String>,
    val parallelSafe: Boolean,
    val lineNumber: Int,
)

enum class OrderingMode {
    ORDERED,
    DEPENDENCIES,
}

enum class RunStatus {
    COMPLETED,
    PARTIAL,
    BLOCKED,
    FAILED,
    UNKNOWN,
}

data class RunResult(
    val taskId: String,
    val exitCode: Int,
    val status: RunStatus,
    val summary: String,
    val commitMessage: String,
    val threadId: String?,
    val logFile: Path,
)

data class CliOptions(
    val projectRoot: Path,
    val planPath: Path,
    val taskPattern: Regex,
    val orderingMode: OrderingMode,
    val parallelism: Int,
    val managedWorktree: Boolean,
    val dryRun: Boolean,
    val maxTasks: Int,
    val codexCommand: String,
    val model: String?,
    val approveForMe: Boolean,
    val commitEach: Boolean,
)
