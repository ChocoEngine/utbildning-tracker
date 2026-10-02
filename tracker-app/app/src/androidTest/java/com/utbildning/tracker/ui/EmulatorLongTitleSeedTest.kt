package com.utbildning.tracker.ui

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.utbildning.tracker.data.TrackerRepository
import com.utbildning.tracker.data.local.SessionEntity
import com.utbildning.tracker.data.local.TrackerDatabase
import com.utbildning.tracker.domain.WeeklyRule
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Additive, idempotent fixture for checking long course names on an emulator. */
@RunWith(AndroidJUnit4::class)
class EmulatorLongTitleSeedTest {
    @Test fun addLongTitleCourse() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("seedLongTitle") == "true")
        check(Build.HARDWARE in listOf("ranchu", "goldfish")) { "Demo seeding requires an emulator" }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val preferences = context.getSharedPreferences("emulator_demo", 0)
        val database = TrackerDatabase.open(context)
        try {
            val repository = TrackerRepository(database)
            val dao = database.trackerDao()
            val key = "long-title"
            val name = "Практика алгоритмов и структур данных на C"
            val priorId = preferences.getString(key, null)
            val course = priorId?.let { repository.getCourse(it) } ?: run {
                val topics = instrumentation.context.assets.open("demo/long-title.txt")
                    .bufferedReader().use { it.readLines().filter(String::isNotBlank) }
                val color = checkNotNull(repository.availableColors().firstOrNull()) { "No free color for the long-title demo course" }
                repository.createCourse(name, color, "Программирование", topics).also { created ->
                    repository.saveInitialSchedule(created.id, (1..7).map { WeeklyRule(it, 17 * 60 + 30) })
                    check(preferences.edit().putString(key, created.id).commit())
                }
            }
            if (InstrumentationRegistry.getArguments().getString("seedToday") == "true") {
                val date = LocalDate.now().toEpochDay()
                if (dao.getSessions(course.id).none { it.date == date }) {
                    val timestamp = System.currentTimeMillis()
                    dao.insertSession(SessionEntity(UUID.randomUUID().toString(), course.id, date, 17 * 60 + 30,
                        course.name, course.colorId, timestamp, timestamp))
                }
            }
        } finally {
            database.close()
        }
    }
}
