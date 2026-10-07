package com.utbildning.orchestrator

import kotlin.test.Test
import kotlin.test.assertEquals

class TaskSchedulerTest {
    @Test
    fun orderedModeRunsOneTaskAfterItsPredecessor() {
        val tasks = listOf(task("F01", completed = true), task("F02"), task("F03"))

        val wave = TaskScheduler(OrderingMode.ORDERED).nextWave(tasks, parallelism = 3)

        assertEquals(listOf("F02"), wave.map(PlanTask::id))
    }

    @Test
    fun dependencyModeRunsIndependentSafeTasksTogether() {
        val tasks = listOf(
            task("T01", parallelSafe = true),
            task("T02", parallelSafe = true),
            task("T03", dependencies = setOf("T01", "T02"), parallelSafe = true),
        )

        val wave = TaskScheduler(OrderingMode.DEPENDENCIES).nextWave(tasks, parallelism = 2)

        assertEquals(listOf("T01", "T02"), wave.map(PlanTask::id))
    }

    @Test
    fun unsafeReadyTaskRunsAlone() {
        val tasks = listOf(task("T01"), task("T02", parallelSafe = true))

        val wave = TaskScheduler(OrderingMode.DEPENDENCIES).nextWave(tasks, parallelism = 2)

        assertEquals(listOf("T01"), wave.map(PlanTask::id))
    }

    private fun task(
        id: String,
        completed: Boolean = false,
        dependencies: Set<String> = emptySet(),
        parallelSafe: Boolean = false,
    ) = PlanTask(
        id = id,
        title = id,
        body = id,
        completed = completed,
        dependencies = dependencies,
        parallelSafe = parallelSafe,
        lineNumber = 1,
    )
}
