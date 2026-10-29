package com.utbildning.orchestrator

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MarkdownTaskPlanTest {
    @Test
    fun parsesMatchingTasksAndMetadata() {
        val plan = Files.createTempFile("orchestrator-plan", ".md")
        Files.writeString(
            plan,
            """
                # Plan

                - [x] **T01 — Done.** Already complete.
                - [ ] **T02 — Work.** Main description.
                  More detail.
                  <!-- codex-task: depends-on=T01; parallel-safe=true -->
                - [ ] **OTHER — Ignored.** Does not match the selected pattern.
            """.trimIndent(),
        )

        val tasks = MarkdownTaskPlan(plan, Regex("T\\d+")).read()

        assertEquals(listOf("T01", "T02"), tasks.map(PlanTask::id))
        assertTrue(tasks[0].completed)
        assertFalse(tasks[1].completed)
        assertEquals(setOf("T01"), tasks[1].dependencies)
        assertTrue(tasks[1].parallelSafe)
        assertTrue(tasks[1].body.contains("More detail."))
    }

    @Test
    fun rejectsUnknownDependencies() {
        val plan = Files.createTempFile("orchestrator-plan", ".md")
        Files.writeString(
            plan,
            """
                - [ ] **T01 — Work.** Description.
                  <!-- codex-task: depends-on=MISSING -->
            """.trimIndent(),
        )

        val error = kotlin.runCatching {
            MarkdownTaskPlan(plan, Regex("T\\d+")).read()
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error.message.orEmpty().contains("T01 -> MISSING"))
    }
}
