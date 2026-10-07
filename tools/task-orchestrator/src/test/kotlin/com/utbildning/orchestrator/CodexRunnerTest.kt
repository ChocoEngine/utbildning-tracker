package com.utbildning.orchestrator

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CodexRunnerTest {
    @Test
    fun approveForMeIsNotCombinedWithExplicitSandbox() {
        val command = runner(approveForMe = true).buildCommand(task(), Path.of("schema"), Path.of("result"))

        assertTrue("--approve-for-me" in command)
        assertFalse("--sandbox" in command)
    }

    @Test
    fun workspaceWriteIsExplicitWithoutAutomaticApproval() {
        val command = runner(approveForMe = false).buildCommand(task(), Path.of("schema"), Path.of("result"))

        assertFalse("--approve-for-me" in command)
        assertTrue(command.windowed(2).any { it == listOf("--sandbox", "workspace-write") })
    }

    private fun runner(approveForMe: Boolean): CodexRunner {
        val root = Files.createTempDirectory("orchestrator-runner")
        return CodexRunner(
            CliOptions(
                projectRoot = root,
                planPath = root.resolve("PLAN.md"),
                taskPattern = Regex("T\\d+"),
                orderingMode = OrderingMode.ORDERED,
                parallelism = 1,
                managedWorktree = false,
                dryRun = false,
                maxTasks = 1,
                codexCommand = "codex",
                model = null,
                approveForMe = approveForMe,
                commitEach = false,
            ),
        )
    }

    private fun task() = PlanTask(
        id = "T01",
        title = "Test",
        body = "Test",
        completed = false,
        dependencies = emptySet(),
        parallelSafe = false,
        lineNumber = 1,
    )
}
