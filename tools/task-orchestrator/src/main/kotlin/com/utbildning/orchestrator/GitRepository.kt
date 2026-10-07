package com.utbildning.orchestrator

import java.nio.file.Path

class GitRepository(
    private val root: Path,
) {
    fun isClean(): Boolean = run("status", "--porcelain").output.isBlank()

    fun head(): String = run("rev-parse", "HEAD").output.trim()

    fun commitAll(subject: String): String {
        validateSubject(subject)
        require(!isClean()) { "Task completed without any working-tree changes to commit" }
        run("add", "-A")
        val staged = runAllowingExitCode("diff", "--cached", "--quiet")
        require(staged.exitCode == 1) {
            "Nothing was staged for commit (git diff exit=${staged.exitCode})"
        }
        run("commit", "-m", subject)
        require(isClean()) { "Checkout is not clean after committing the completed task" }
        return run("rev-parse", "--short", "HEAD").output.trim()
    }

    private fun validateSubject(subject: String) {
        require(subject.isNotBlank()) { "Completed task did not provide a commit message" }
        require('\n' !in subject && '\r' !in subject) { "Commit subject must be a single line" }
        require(subject.length <= 100) { "Commit subject is longer than 100 characters" }
    }

    private fun run(vararg arguments: String): CommandResult {
        val result = runAllowingExitCode(*arguments)
        require(result.exitCode == 0) {
            "git ${arguments.joinToString(" ")} failed (${result.exitCode}): ${result.output.trim()}"
        }
        return result
    }

    private fun runAllowingExitCode(vararg arguments: String): CommandResult {
        val process = ProcessBuilder(listOf("git") + arguments)
            .directory(root.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        return CommandResult(process.waitFor(), output)
    }

    private data class CommandResult(
        val exitCode: Int,
        val output: String,
    )
}
