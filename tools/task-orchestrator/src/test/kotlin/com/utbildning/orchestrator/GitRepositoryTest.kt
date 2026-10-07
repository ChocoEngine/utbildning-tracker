package com.utbildning.orchestrator

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GitRepositoryTest {
    @Test
    fun commitsAllChangesAndReturnsToCleanState() {
        val root = Files.createTempDirectory("orchestrator-git")
        git(root, "init")
        git(root, "config", "user.name", "Orchestrator Test")
        git(root, "config", "user.email", "orchestrator@example.invalid")
        Files.writeString(root.resolve("initial.txt"), "initial\n")
        git(root, "add", "initial.txt")
        git(root, "commit", "-m", "Initial")
        Files.writeString(root.resolve("task.txt"), "result\n")
        val repository = GitRepository(root)

        assertFalse(repository.isClean())
        val commit = repository.commitAll("Complete test task")

        assertTrue(commit.isNotBlank())
        assertTrue(repository.isClean())
        assertEquals("Complete test task", git(root, "log", "-1", "--pretty=%s").trim())
    }

    private fun git(root: Path, vararg arguments: String): String {
        val process = ProcessBuilder(listOf("git") + arguments)
            .directory(root.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { output }
        return output
    }
}
