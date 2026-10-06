package com.utbildning.tracker.data

import com.utbildning.tracker.data.local.CourseEntity
import com.utbildning.tracker.data.local.ScheduleRuleEntity

/** Consistent list data loaded by a fixed number of batch queries. */
data class CoursesSnapshot(
    val courses: List<CourseEntity>,
    val progress: Map<String, Pair<Int, Int>>,
    val scheduleRules: Map<String, List<ScheduleRuleEntity>>,
)

data class CourseTopicCounts(val courseId: String, val completed: Int, val total: Int)
data class CourseDoneCount(val courseId: String, val done: Int)
