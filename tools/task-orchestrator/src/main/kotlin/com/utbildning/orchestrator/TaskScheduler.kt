package com.utbildning.orchestrator

class TaskScheduler(
    private val orderingMode: OrderingMode,
) {
    fun nextWave(tasks: List<PlanTask>, parallelism: Int): List<PlanTask> {
        require(parallelism > 0) { "parallelism must be positive" }
        val completed = tasks.filter(PlanTask::completed).mapTo(mutableSetOf(), PlanTask::id)
        val incomplete = tasks.filterNot(PlanTask::completed)
        if (incomplete.isEmpty()) return emptyList()

        val ready = incomplete.filter { task ->
            effectiveDependencies(task, tasks).all(completed::contains)
        }
        require(ready.isNotEmpty()) {
            val blocked = incomplete.joinToString { task ->
                val missing = effectiveDependencies(task, tasks).filterNot(completed::contains)
                "${task.id} waits for ${missing.joinToString()}"
            }
            "No ready tasks. Dependency cycle or unfinished prerequisite: $blocked"
        }

        val first = ready.first()
        if (parallelism == 1 || !first.parallelSafe) return listOf(first)
        return ready.takeWhile(PlanTask::parallelSafe).take(parallelism)
    }

    fun effectiveDependencies(task: PlanTask, tasks: List<PlanTask>): Set<String> {
        if (orderingMode == OrderingMode.DEPENDENCIES) return task.dependencies
        val index = tasks.indexOfFirst { it.id == task.id }
        val predecessor = tasks.getOrNull(index - 1)?.id
        return if (predecessor == null) task.dependencies else task.dependencies + predecessor
    }
}
