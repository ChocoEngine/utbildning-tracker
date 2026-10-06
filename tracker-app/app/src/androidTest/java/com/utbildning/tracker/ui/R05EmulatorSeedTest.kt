package com.utbildning.tracker.ui

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import com.utbildning.tracker.data.TrackerRepository
import com.utbildning.tracker.data.local.*
import com.utbildning.tracker.domain.WeeklyRule
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in, idempotent fixture for the dedicated acceptance emulator. */
class R05EmulatorSeedTest {
    @Test fun seedAcceptanceCourses() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("seedR05") == "true")
        check(Build.HARDWARE in listOf("ranchu", "goldfish"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = TrackerDatabase.open(context)
        try {
            val repo = TrackerRepository(db)
            val dao = db.trackerDao()
            val prefs = context.getSharedPreferences("r05_fixture", 0)
            val names = listOf("Лекции и практика программирования на C", "C pointers arrays memory structured programming",
                "Практика C без тем", "C без расписания", "C на паузе", "C все темы пройдены", "C пустой курс", "C завершённый")
            val ids = names.mapIndexed { index, name ->
                val key = "course_$index"
                prefs.getString(key, null)?.takeIf { dao.getCourse(it) != null } ?: run {
                    val topics = if (index in listOf(2, 6)) emptyList() else (1..30).map {
                        "Тема $it: указатели, массивы и управление памятью в C"
                    }
                    val course = repo.createCourse(name, repo.availableColors().first(),
                        if (index == 6) "" else "Программирование и самостоятельная практика", topics)
                    if (index in listOf(0, 1, 2, 4)) repo.saveInitialSchedule(course.id,
                        (1..7).map { WeeklyRule(it, 600 + index * 90, if (index == 1) 60 else 660 + index * 90) },
                        LocalDate.now().plusDays(45).toEpochDay())
                    if (index in listOf(0, 1, 3, 4, 5)) dao.getTopics(course.id).take(if (index == 5) 30 else index + 1)
                        .forEach { repo.toggleTopicCompletion(course.id, it.id) }
                    if (index == 4) repo.pauseCourse(course.id)
                    if (index == 7) repo.completeCourse(course.id)
                    check(prefs.edit().putString(key, course.id).commit())
                    course.id
                }
            }
            val today = LocalDate.now().toEpochDay()
            for (offset in 0..3) for (index in 0..4) {
                if (offset == 1 && index == 4) continue
                val course = checkNotNull(dao.getCourse(ids[index]))
                if (dao.getSessions(course.id).any { it.date == today - offset }) continue
                val result = when {
                    offset == 1 && index < 2 -> SessionResult.DONE
                    offset == 1 -> SessionResult.SKIPPED
                    offset == 2 && index == 0 -> SessionResult.DONE
                    offset >= 2 -> SessionResult.SKIPPED
                    else -> SessionResult.PENDING
                }
                val now = System.currentTimeMillis()
                dao.insertSession(SessionEntity("r05_${today - offset}_$index", course.id, today - offset,
                    600 + index * 90, course.name, course.colorId, now, now, result = result))
            }
            if (!prefs.getBoolean("results_$today", false)) {
                for ((index, result) in listOf(0 to SessionResult.DONE, 1 to SessionResult.SKIPPED)) {
                    dao.getSessions(ids[index]).firstOrNull { it.date == today && it.result == SessionResult.PENDING }
                        ?.let { repo.setSessionResult(it.id, result, emptySet()) }
                }
                check(prefs.edit().putBoolean("results_$today", true).commit())
            }
            println("R05_SEED courses=${ids.size}; calendar=4/5 courses; date=$today")
        } finally { db.close() }
    }
}
