package com.utbildning.orchestrator

import java.nio.file.Files
import java.nio.file.Path

class MarkdownTaskPlan(
    private val planPath: Path,
    private val taskPattern: Regex,
) {
    private val taskLine = Regex(
        """^\s*-\s+\[([ xX])]\s+\*\*([A-Za-z][A-Za-z0-9_.-]*)\s+[—-]\s+(.+?)\*\*(.*)$""",
    )
    private val metadataLine = Regex("""<!--\s*codex-task:\s*(.*?)\s*-->""")

    fun read(): List<PlanTask> {
        require(Files.isRegularFile(planPath)) { "Plan file does not exist: $planPath" }
        val lines = Files.readAllLines(planPath)
        val starts = lines.mapIndexedNotNull { index, line ->
            taskLine.matchEntire(line)?.let { index to it }
        }

        val tasks = starts.mapIndexedNotNull { index, (lineIndex, match) ->
            val id = match.groupValues[2]
            if (!taskPattern.matches(id)) return@mapIndexedNotNull null

            val nextLineIndex = starts.getOrNull(index + 1)?.first ?: lines.size
            val blockLines = lines.subList(lineIndex, nextLineIndex)
            val metadata = blockLines
                .mapNotNull { metadataLine.find(it)?.groupValues?.get(1) }
                .flatMap(::parseMetadata)
                .toMap()
            val dependencies = metadata["depends-on"]
                .orEmpty()
                .split(',')
                .map(String::trim)
                .filter(String::isNotEmpty)
                .toSet()
            val parallelSafe = metadata["parallel-safe"]?.toBooleanStrictOrNull() ?: false
            val body = buildString {
                append(blockLines.first().replace(metadataLine, "").trim())
                blockLines.drop(1)
                    .filterNot { metadataLine.containsMatchIn(it) }
                    .forEach { append('\n').append(it.trimEnd()) }
            }.trim()

            PlanTask(
                id = id,
                title = match.groupValues[3].trim(),
                body = body,
                completed = match.groupValues[1].isNotBlank(),
                dependencies = dependencies,
                parallelSafe = parallelSafe,
                lineNumber = lineIndex + 1,
            )
        }

        val duplicates = tasks.groupingBy(PlanTask::id).eachCount().filterValues { it > 1 }.keys
        require(duplicates.isEmpty()) { "Duplicate task ids in $planPath: ${duplicates.sorted().joinToString()}" }
        val ids = tasks.mapTo(mutableSetOf(), PlanTask::id)
        val missingDependencies = tasks
            .flatMap { task -> task.dependencies.filterNot(ids::contains).map { task.id to it } }
        require(missingDependencies.isEmpty()) {
            "Unknown dependencies: " + missingDependencies.joinToString { (task, dependency) -> "$task -> $dependency" }
        }
        return tasks
    }

    private fun parseMetadata(raw: String): List<Pair<String, String>> = raw
        .split(';')
        .mapNotNull { entry ->
            val separator = entry.indexOf('=')
            if (separator < 1) return@mapNotNull null
            entry.substring(0, separator).trim() to entry.substring(separator + 1).trim()
        }
}
