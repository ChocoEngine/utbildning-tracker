package com.utbildning.tracker.data

import com.utbildning.tracker.data.local.CategoryEntity
import com.utbildning.tracker.data.local.CourseEntity
import com.utbildning.tracker.data.local.TopicCompletionEntity
import com.utbildning.tracker.data.local.TopicEntity

/** Transactionally consistent snapshot for the course form. Topics exclude archived rows. */
data class CourseDetails(
    val course: CourseEntity,
    val topics: List<TopicEntity>,
    val completions: List<TopicCompletionEntity>,
    val category: CategoryEntity?,
)
