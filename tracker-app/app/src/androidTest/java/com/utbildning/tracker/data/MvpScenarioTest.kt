package com.utbildning.tracker.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.utbildning.tracker.data.local.*
import com.utbildning.tracker.domain.TopicListConflictException
import com.utbildning.tracker.domain.WeeklyRule
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MvpScenarioTest {
    @Test fun homeworkLecturesPracticeAndSelfStudySurviveEditingReopenAndLifecycle() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "mvp-scenario.db"
        context.deleteDatabase(databaseName)
        var db = TrackerDatabase.open(context, databaseName)
        var timestamp = Instant.parse("2026-09-28T10:00:00Z").toEpochMilli()
        fun repository() = TrackerRepository(db, now = { timestamp }, zone = { ZoneId.of("UTC") })
        var repo = repository()
        try {
            val homework = repo.saveCourseForm(name = "Домашка по C", colorId = 0,
                categoryName = "Программирование", topicText = (1..40).joinToString("\n") { "Задача по C $it" })
            val lectures = repo.saveCourseForm(name = "Лекции по C", colorId = 1,
                categoryName = " программирование ", topicText = "Массивы\nУказатели\nСтруктуры")
            val practice = repo.saveCourseForm(name = "Практика C", colorId = 2,
                categoryName = "Программирование")
            val selfStudy = repo.saveCourseForm(name = "C · Темы для самостоятельного изучения", colorId = 3,
                categoryName = "Программирование",
                topicText = (1..20).joinToString("\n") { "Тема C $it" })
            assertEquals(setOf(homework.categoryId), setOf(lectures.categoryId, practice.categoryId, selfStudy.categoryId))
            repo.saveInitialSchedule(homework.id, listOf(WeeklyRule(1, 720), WeeklyRule(4, 1080)))
            repo.saveInitialSchedule(lectures.id, listOf(WeeklyRule(3, 600)))
            repo.saveInitialSchedule(practice.id, listOf(WeeklyRule(2, 900)))
            val homeworkTopics = repo.getCourseDetails(homework.id)!!.topics
            val sessions = db.trackerDao().getSessions(homework.id)
            val four = listOf(0, 3, 11, 25).map { homeworkTopics[it].id }.toSet()
            repo.setSessionResult(sessions[0].id, SessionResult.DONE, four)
            timestamp = java.time.LocalDate.ofEpochDay(sessions[1].date).atTime(10, 0).toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
            repo.setSessionResult(sessions[1].id, SessionResult.DONE, emptySet())
            assertEquals(4, repo.getCourseDetails(homework.id)!!.topics.filter { it.isCompleted }.size)
            assertEquals(40, repo.getCourseDetails(homework.id)!!.topics.size)
            assertEquals(SessionResult.DONE, repo.getSessionDetails(sessions[0].id)!!.session.result)
            assertEquals(SessionResult.DONE, repo.getSessionDetails(sessions[1].id)!!.session.result)
            assertTrue(repo.getSessionDetails(sessions[1].id)!!.selectedTopicIds.isEmpty())
            timestamp = java.time.LocalDate.ofEpochDay(sessions[2].date).atTime(10, 0).toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
            repo.setSessionResult(sessions[2].id, SessionResult.SKIPPED)
            repo.setSessionResult(sessions[2].id, SessionResult.DONE, setOf(homeworkTopics[1].id, homeworkTopics[8].id))
            repo.setSessionResult(sessions[2].id, SessionResult.DONE, setOf(homeworkTopics[1].id, homeworkTopics[9].id))
            assertEquals(6, repo.getCourseDetails(homework.id)!!.topics.filter { it.isCompleted }.size)
            assertNull(db.trackerDao().getTopic(homeworkTopics[8].id)?.takeIf { it.isCompleted })

            val beforeEdit = repo.getCourseDetails(homework.id)!!
            try {
                repo.saveCourseForm(homework.id, "Should roll back", 4, "New category",
                    "  ${homeworkTopics[0].title.uppercase()}  ")
                fail("Completed-topic conflict must reject the whole form")
            } catch (conflict: TopicListConflictException) { assertEquals(listOf(1), conflict.lineNumbers) }
            assertEquals(beforeEdit, repo.getCourseDetails(homework.id))
            val completed = beforeEdit.topics.filter { it.isCompleted }.map { it.id }.toSet()
            val editable = beforeEdit.topics.filter { it.id !in completed }.reversed()
            repo.saveTopicList(homework.id, editable.joinToString("\n") { it.title } + "\nНовая задача по C")
            val edited = repo.getCourseDetails(homework.id)!!
            assertEquals(41, edited.topics.size)
            assertEquals(beforeEdit.topics.filter { it.isCompleted }, edited.topics.filter { it.isCompleted })
            assertTrue(edited.topics.map { it.id }.containsAll(homeworkTopics.map { it.id }))

            val lectureSession = db.trackerDao().getSessions(lectures.id).first()
            val lectureTopics = repo.getCourseDetails(lectures.id)!!.topics
            timestamp = java.time.LocalDate.ofEpochDay(lectureSession.date).atTime(10, 0).toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
            repo.setSessionResult(lectureSession.id, SessionResult.DONE, lectureTopics.take(2).map { it.id }.toSet())
            val practiceSession = db.trackerDao().getSessions(practice.id).first()
            timestamp = java.time.LocalDate.ofEpochDay(practiceSession.date).atTime(10, 0).toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
            repo.setSessionResult(practiceSession.id, SessionResult.DONE, emptySet())
            assertTrue(repo.getCourseDetails(practice.id)!!.topics.isEmpty())
            val selfTopics = repo.getCourseDetails(selfStudy.id)!!.topics
            repo.toggleTopicCompletion(selfStudy.id, selfTopics[19].id)
            repo.toggleTopicCompletion(selfStudy.id, selfTopics[2].id)
            repo.toggleTopicCompletion(selfStudy.id, selfTopics[19].id)
            assertEquals(listOf(selfTopics[2].id), repo.getCourseDetails(selfStudy.id)!!.topics.filter { it.isCompleted }.map { it.id })
            assertTrue(db.trackerDao().getSessions(selfStudy.id).isEmpty())
            assertNull(repo.getSchedule(selfStudy.id))

            db.close()
            db = TrackerDatabase.open(context, databaseName)
            repo = repository()
            assertEquals(edited, repo.getCourseDetails(homework.id))
            assertEquals(20, repo.getCourseDetails(selfStudy.id)!!.topics.size)
            assertEquals(listOf(selfTopics[2].id), repo.getCourseDetails(selfStudy.id)!!.topics.filter { it.isCompleted }.map { it.id })
            repo.pauseCourse(homework.id)
            assertEquals(6, repo.getCourseDetails(homework.id)!!.topics.filter { it.isCompleted }.size)
            assertNull(repo.getSchedule(homework.id))
            assertNotNull(db.trackerDao().getCourse(homework.id))
            repo.completeCourse(lectures.id)
            assertEquals(3, repo.getCourseDetails(lectures.id)!!.topics.filter { it.isCompleted }.size)
            assertEquals(SessionResult.DONE, repo.getSessionDetails(lectureSession.id)!!.session.result)
            assertNull(db.trackerDao().getCourse(lectures.id)!!.colorId)
            repo.deleteCourse(practice.id)
            assertNull(repo.getCourseDetails(practice.id))
            assertTrue(db.trackerDao().getSessions(practice.id).isEmpty())
            assertTrue(repo.availableColors().containsAll(listOf(1, 2)))
            assertNotNull(repo.getCourseDetails(selfStudy.id))
        } finally {
            db.close()
            context.deleteDatabase(databaseName)
        }
    }
}
