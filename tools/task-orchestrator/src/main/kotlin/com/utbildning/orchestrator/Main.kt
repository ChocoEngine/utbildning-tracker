package com.utbildning.orchestrator

import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.io.path.absolute
import kotlin.io.path.exists

fun main(args: Array<String>) {
    val exitCode = try {
        Orchestrator(parseOptions(args)).run()
    } catch (error: IllegalArgumentException) {
        System.err.println("Configuration error: ${error.message}")
        2
    } catch (error: Exception) {
        error.printStackTrace(System.err)
        1
    }
    if (exitCode != 0) kotlin.system.exitProcess(exitCode)
}

class Orchestrator(
    private val options: CliOptions,
) {
    private val plan = MarkdownTaskPlan(options.planPath, options.taskPattern)
    private val scheduler = TaskScheduler(options.orderingMode)
    private val repository = GitRepository(options.projectRoot)

    fun run(): Int {
        if (options.dryRun) return preview()
        return RunLock.acquire(options.projectRoot).use {
            require(options.parallelism == 1 || options.managedWorktree) {
                "Parallel live runs require --worktree so tasks cannot edit the same checkout"
            }
            require(!options.commitEach || !options.managedWorktree) {
                "--commit-each cannot be combined with --worktree; integrate worktree results first"
            }
            if (options.managedWorktree || options.commitEach) {
                require(repository.isClean()) {
                    "Managed-worktree and commit-each modes require a clean Git checkout"
                }
            }
            execute()
        }
    }

    private fun preview(): Int {
        var tasks = plan.read()
        var waveNumber = 1
        while (tasks.any { !it.completed }) {
            val wave = scheduler.nextWave(tasks, options.parallelism)
            println("Wave $waveNumber: ${wave.joinToString { it.id }}")
            val completedIds = wave.mapTo(mutableSetOf(), PlanTask::id)
            tasks = tasks.map { if (it.id in completedIds) it.copy(completed = true) else it }
            waveNumber++
        }
        if (waveNumber == 1) println("No unfinished matching tasks")
        return 0
    }

    private fun execute(): Int {
        var launched = 0
        while (launched < options.maxTasks) {
            val before = plan.read()
            if (before.none { !it.completed }) {
                println("All matching tasks are complete")
                return 0
            }
            val wave = scheduler.nextWave(before, options.parallelism)
                .take(options.maxTasks - launched)
            val headBefore = if (options.commitEach) repository.head() else null
            println("Starting: ${wave.joinToString { it.id }}")
            val results = runWave(wave)
            launched += wave.size
            results.forEach(::printResult)

            if (options.managedWorktree) {
                val failed = results.firstOrNull { result ->
                    result.exitCode != 0 || result.status != RunStatus.COMPLETED
                }
                if (failed != null) {
                    System.err.println(
                        "Worktree run stopped after ${failed.taskId}: exit=${failed.exitCode}, " +
                            "status=${failed.status}.",
                    )
                    return 4
                }
                println(
                    "Managed-worktree wave finished. Results remain isolated; " +
                        "review and integrate the reported threads before continuing.",
                )
                return 3
            }

            val after = plan.read().associateBy(PlanTask::id)
            val failed = results.firstOrNull { result ->
                result.exitCode != 0 ||
                    result.status != RunStatus.COMPLETED ||
                    after[result.taskId]?.completed != true
            }
            if (failed != null) {
                System.err.println(
                    "Stopped after ${failed.taskId}: exit=${failed.exitCode}, " +
                        "status=${failed.status}; the plan checkbox must also be complete.",
                )
                return 4
            }
            if (options.commitEach) {
                require(repository.head() == headBefore) {
                    "The task created a commit directly; the orchestrator expected to commit it"
                }
                val result = results.single()
                val commit = repository.commitAll(result.commitMessage)
                println("Committed ${result.taskId} as $commit: ${result.commitMessage}")
            }
        }
        println("Stopped after reaching --max-tasks=${options.maxTasks}")
        return 0
    }

    private fun runWave(tasks: List<PlanTask>): List<RunResult> {
        if (tasks.size == 1) return listOf(CodexRunner(options).run(tasks.single()))
        val executor = Executors.newFixedThreadPool(tasks.size)
        return try {
            executor.invokeAll(tasks.map { task -> Callable { CodexRunner(options).run(task) } })
                .map { it.get() }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun printResult(result: RunResult) {
        println(
            buildString {
                append(result.taskId)
                append(": ")
                append(result.status)
                append(", exit=")
                append(result.exitCode)
                result.threadId?.let { append(", thread=").append(it) }
                append(", log=").append(result.logFile)
                if (result.summary.isNotBlank()) append("\n  ").append(result.summary)
            },
        )
    }

}

private fun parseOptions(args: Array<String>): CliOptions {
    if (args.contains("--help") || args.contains("-h")) {
        println(USAGE)
        kotlin.system.exitProcess(0)
    }
    var project = Path.of("").absolute().normalize()
    var plan = "FEATURE_PLAN.md"
    var pattern = "[A-Za-z][A-Za-z0-9_.-]*"
    var ordering = OrderingMode.ORDERED
    var parallelism = 1
    var managedWorktree = false
    var dryRun = false
    var maxTasks = Int.MAX_VALUE
    var codex = "codex"
    var model: String? = null
    var approveForMe = false
    var commitEach = false

    var index = 0
    fun value(option: String): String {
        require(index + 1 < args.size) { "Missing value for $option" }
        index++
        return args[index]
    }
    while (index < args.size) {
        when (val argument = args[index]) {
            "--project" -> project = Path.of(value(argument)).absolute().normalize()
            "--plan" -> plan = value(argument)
            "--task-pattern" -> pattern = value(argument)
            "--ordering" -> ordering = when (value(argument)) {
                "ordered" -> OrderingMode.ORDERED
                "dependencies" -> OrderingMode.DEPENDENCIES
                else -> throw IllegalArgumentException("--ordering must be ordered or dependencies")
            }
            "--parallelism" -> parallelism = value(argument).toInt()
            "--worktree" -> managedWorktree = true
            "--dry-run" -> dryRun = true
            "--max-tasks" -> maxTasks = value(argument).toInt()
            "--codex" -> codex = value(argument)
            "--model" -> model = value(argument)
            "--approve-for-me" -> approveForMe = true
            "--commit-each" -> commitEach = true
            else -> throw IllegalArgumentException("Unknown argument: $argument")
        }
        index++
    }

    require(project.exists()) { "Project root does not exist: $project" }
    require(parallelism > 0) { "--parallelism must be positive" }
    require(maxTasks > 0) { "--max-tasks must be positive" }
    val planPath = project.resolve(plan).normalize()
    require(planPath.startsWith(project)) { "Plan must be inside the project root" }

    return CliOptions(
        projectRoot = project,
        planPath = planPath,
        taskPattern = Regex(pattern),
        orderingMode = ordering,
        parallelism = parallelism,
        managedWorktree = managedWorktree,
        dryRun = dryRun,
        maxTasks = maxTasks,
        codexCommand = codex,
        model = model,
        approveForMe = approveForMe,
        commitEach = commitEach,
    )
}

private class RunLock private constructor(
    private val channel: FileChannel,
    private val lock: FileLock,
) : AutoCloseable {
    override fun close() {
        lock.release()
        channel.close()
    }

    companion object {
        fun acquire(projectRoot: Path): RunLock {
            val stateDir = projectRoot.resolve(".codex-orchestrator")
            Files.createDirectories(stateDir)
            val channel = FileChannel.open(
                stateDir.resolve("orchestrator.lock"),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
            )
            val lock = try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            }
            if (lock == null) {
                channel.close()
                throw IllegalArgumentException("Another orchestrator is already running for $projectRoot")
            }
            return RunLock(channel, lock)
        }
    }
}

private val USAGE = """
    Kotlin task orchestrator for Markdown checklists.

    Usage:
      task-orchestrator [options]

    Options:
      --project DIR              Project root (default: current directory)
      --plan FILE                Markdown plan relative to project (default: FEATURE_PLAN.md)
      --task-pattern REGEX       Full task-id regex
      --ordering MODE            ordered | dependencies (default: ordered)
      --parallelism N            Maximum tasks in one wave (default: 1)
      --worktree                 Run Codex in managed Git worktrees
      --dry-run                  Print execution waves without starting Codex
      --max-tasks N              Stop after at most N tasks
      --codex PATH               Codex executable (default: codex)
      --model MODEL              Optional Codex model override
      --approve-for-me           Let Codex automatically review approval requests
      --commit-each              Commit each completed task before starting the next
      --help                     Show this help

    Metadata on a task item:
      <!-- codex-task: depends-on=T01,T02; parallel-safe=true -->
""".trimIndent()
