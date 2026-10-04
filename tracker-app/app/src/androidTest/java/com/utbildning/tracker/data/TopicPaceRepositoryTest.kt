package com.utbildning.tracker.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.utbildning.tracker.data.local.*
import com.utbildning.tracker.domain.WeeklyRule
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TopicPaceRepositoryTest {
    @Test fun pendingTodayCountsAndResultsTopicsAndScheduleRefreshPace() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, TrackerDatabase::class.java).build()
        try {
            val date = LocalDate.of(2026, 10, 6)
            val zone = ZoneId.of("Europe/Moscow")
            val repository = TrackerRepository(db, now = { date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli() }, zone = { zone })
            val course = repository.saveCourseForm(name = "C", colorId = 0, topicText = (1..5).joinToString("\n") { "Topic $it" })
            val rules = listOf(WeeklyRule(2, 1140))
            repository.saveInitialSchedule(course.id, rules, date.plusWeeks(1).toEpochDay())
            assertEquals(3, repository.observeTopicPaces().first()[course.id])
            val session = db.trackerDao().getAllSessions().first { it.date == date.toEpochDay() }
            repository.setSessionResult(session.id, SessionResult.SKIPPED)
            assertEquals(5, repository.observeTopicPaces().first()[course.id])
            val topic = db.trackerDao().getTopics(course.id).first()
            repository.toggleTopicCompletion(course.id, topic.id)
            assertEquals(4, repository.observeTopicPaces().first()[course.id])
            repository.updateSchedule(course.id, rules, date.plusWeeks(10).toEpochDay())
            assertEquals(1, repository.observeTopicPaces().first()[course.id])
            repository.updateSchedule(course.id, rules)
            assertFalse(repository.observeTopicPaces().first().containsKey(course.id))
        } finally { db.close() }
    }
}
