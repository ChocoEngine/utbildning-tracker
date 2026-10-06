package com.utbildning.tracker.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.utbildning.tracker.data.TrackerRepository
import com.utbildning.tracker.data.local.*
import com.utbildning.tracker.domain.WeeklyRule
import com.utbildning.tracker.ui.calendar.CalendarScreen
import com.utbildning.tracker.ui.theme.TrackerTheme
import java.time.YearMonth
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CalendarIntegrationTest {
    @get:Rule val compose = createComposeRule(effectContext = StandardTestDispatcher())
    private lateinit var database: TrackerDatabase
    private lateinit var repository: TrackerRepository
    private val visible = mutableStateOf(true)
    private val dao get() = database.trackerDao()

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TrackerDatabase::class.java).build()
        repository = TrackerRepository(database)
    }
    @After fun tearDown() {
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        database.close()
    }

    @Test fun openingFarMonthGeneratesReadOnlyCalendarAndRejectsResultChanges() {
        val target = YearMonth.now().plusMonths(6).atDay(1)
        val course = runBlocking {
            repository.createCourse("C", 0).also {
                repository.saveInitialSchedule(it.id, (1..7).map { day -> WeeklyRule(day, 1140) })
            }
        }
        assertTrue(runBlocking { dao.getSessions(course.id) }.none { it.date == target.toEpochDay() })
        compose.setContent { TrackerTheme { if (visible.value) CalendarScreen({}, repository) } }
        repeat(6) { compose.onNodeWithTag("calendar_grid").performTouchInput { swipeLeft() } }
        compose.waitUntil(10_000) { runBlocking { dao.getSessions(course.id) }.any { it.date == target.toEpochDay() } }
        val session = runBlocking { dao.getSessions(course.id) }.single { it.date == target.toEpochDay() }
        click("calendar_day_${target.toEpochDay()}")
        compose.onNodeWithTag("calendar_session_${session.id}").assertHasNoClickAction()
        try {
            runBlocking { repository.setSessionResult(session.id, SessionResult.SKIPPED) }
            fail("Future sessions must be read-only")
        } catch (expected: com.utbildning.tracker.data.RepositoryException) {
            assertEquals(com.utbildning.tracker.data.RepositoryError.INVALID_SESSION_DATE, expected.error)
        }
        assertEquals(SessionResult.PENDING, runBlocking { dao.getSession(session.id) }!!.result)
        assertEquals(1, runBlocking { dao.getSessions(course.id) }.count { it.date == target.toEpochDay() })
    }

    private fun click(tag: String) {
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(tag).performScrollTo().performClick()
    }
}
