package com.utbildning.tracker.ui

import android.os.Build
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.utbildning.tracker.data.TrackerRepository
import com.utbildning.tracker.data.local.SessionEntity
import java.time.LocalDate
import java.util.UUID
import com.utbildning.tracker.data.local.TrackerDatabase
import com.utbildning.tracker.domain.WeeklyRule
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit opt-in fixture for the emulator's real app database, never normal startup. */
@RunWith(AndroidJUnit4::class)
class EmulatorDemoSeedTest {
    @Test fun seedDemoCourses() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("seedDemo") == "true")
        check(Build.HARDWARE in listOf("ranchu", "goldfish")) { "Demo seeding requires an emulator" }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val preferences = context.getSharedPreferences("emulator_demo", 0)
        val database = TrackerDatabase.open(context)
        try {
            val repository = TrackerRepository(database)
            val definitions = listOf(
                Triple("landscapes", "Пейзажи", "Рисование"),
                Triple("programming", "Задачи по C", "Программирование"),
                Triple("swedish", "Шведский · A2", "Языки"),
            )
            for ((key, name, category) in definitions) {
                val priorId = preferences.getString(key, null)
                if (priorId != null && repository.getCourse(priorId) != null) continue
                val topics = instrumentation.context.assets.open("demo/$key.txt").bufferedReader().use { it.readLines().filter(String::isNotBlank) }
                val course = database.withTransaction {
                    val used = database.trackerDao().getCourses().filter { !it.isCompleted }.map { it.colorId }.toSet()
                    val color = (0..9).first { it !in used }
                    val scheduled = key != "programming"
                    val created = repository.createCourse(name, color, category, topics)
                    if (scheduled) repository.saveInitialSchedule(created.id, (1..7).map { WeeklyRule(it, if (key == "swedish") 20 * 60 + 30 else 11 * 60) })
                    check(repository.getCourseDetails(created.id)!!.topics.map { it.title } == topics)
                    created
                }
                check(preferences.edit().putString(key, course.id).commit())
            }
            if (InstrumentationRegistry.getArguments().getString("seedToday") == "true") {
                database.withTransaction {
                    val date = LocalDate.now().toEpochDay()
                    val dao = database.trackerDao()
                    val timestamp = System.currentTimeMillis()
                    val examples = listOf("landscapes" to 9 * 60, "programming" to 15 * 60, "swedish" to (20 * 60 + 30))
                    for ((key, minute) in examples) {
                        val course = checkNotNull(repository.getCourse(checkNotNull(preferences.getString(key, null))))
                        if (repository.getSchedule(course.id) == null) {
                            // Keep the fixture usable with the original schedule API as well.
                            repository.saveInitialSchedule(course.id, (1..7).map { WeeklyRule(it, minute) })
                        }
                        val today = dao.getCourses().flatMap { dao.getSessions(it.id) }.filter { it.date == date }
                        if (today.any { it.courseId == course.id }) continue
                        val original = today.firstOrNull { it.startMinute == minute && it.courseId == preferences.getString("landscapes", null) && it.result == com.utbildning.tracker.data.local.SessionResult.PENDING && dao.getTopics(it.courseId).none { topic -> topic.completionDate == it.date } }
                        if (original != null) {
                            dao.updateSession(original.copy(courseId = course.id, courseName = course.name, colorId = course.colorId, updatedAt = timestamp))
                        } else {
                            dao.insertSession(SessionEntity(UUID.randomUUID().toString(), course.id, date, minute, course.name, course.colorId, timestamp, timestamp))
                        }
                    }
                }
            }
            // Additional idempotent fixtures; never rewrite existing user courses/results.
            val dao = database.trackerDao()
            for ((key, name, completed) in listOf(Triple("reading", "Чтение", false), Triple("practice", "Практика C", false), Triple("finished", "Завершённый пример", true))) {
                val prior = preferences.getString(key, null)
                if (prior != null && dao.getCourse(prior) != null) continue
                val colors = repository.availableColors()
                if (colors.isEmpty()) continue
                val course = repository.createCourse(name, colors.first(), topics = listOf("Первая тема", "Вторая тема"))
                if (completed) repository.completeCourse(course.id)
                check(preferences.edit().putString(key, course.id).commit())
            }
            val date = LocalDate.now().toEpochDay()
            val fixtureIds = listOf("landscapes", "programming", "swedish", "reading", "practice").mapNotNull { preferences.getString(it, null) }
            for ((index, id) in fixtureIds.withIndex()) {
                val course = dao.getCourse(id) ?: continue
                for (offset in listOf(1L, 2L, 3L)) {
                    if (offset == 1L && index == 4) continue
                    val day = date - offset
                    if (dao.getSessions(id).any { it.date == day }) continue
                    val result = if ((offset == 1L && index < 2) || (offset == 2L && index == 0)) com.utbildning.tracker.data.local.SessionResult.DONE else com.utbildning.tracker.data.local.SessionResult.SKIPPED
                    val timestamp = System.currentTimeMillis()
                    dao.insertSession(SessionEntity(UUID.randomUUID().toString(), id, day, 600 + index * 90, course.name, course.colorId, timestamp, timestamp, result = result))
                }
            }
        } finally {
            database.close()
        }
    }
}
