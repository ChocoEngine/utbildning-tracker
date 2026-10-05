package com.utbildning.tracker.notifications

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.utbildning.tracker.data.AppContainer
import com.utbildning.tracker.data.local.SessionResult
import com.utbildning.tracker.domain.WeeklyRule
import java.time.ZonedDateTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Default run checks recovery idempotence. Explicit phases allow a real adb reboot between runs. */
class RecoveryProbeTest {
    @Test fun recoveryAndPermissionIndependence() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = AppContainer.repository(context)
        val preferences = context.getSharedPreferences("recovery_probe", Context.MODE_PRIVATE)
        val phase = InstrumentationRegistry.getArguments().getString("recovery_phase")
        if (phase == null || phase == "prepare") {
            val course = repository.createCourse("Recovery probe C", repository.availableColors().first())
            val next = ZonedDateTime.now().plusMinutes(3).withSecond(0).withNano(0)
            repository.saveInitialSchedule(course.id, listOf(WeeklyRule(next.dayOfWeek.value, next.hour * 60 + next.minute)))
            val ids = repository.observeSessions().first().filter { it.courseId == course.id }.map { it.id }.toSet()
            preferences.edit().putString("course", course.id).putStringSet("sessions", ids)
                .putLong("trigger", next.toInstant().toEpochMilli()).commit()
            ReminderScheduler.receive(context, null, repository)
        }
        val id = preferences.getString("course", null) ?: error("Run prepare before the external recovery phase")
        assertNotNull(repository.getCourse(id))
        assertEquals(preferences.getStringSet("sessions", emptySet()), repository.observeSessions().first().filter { it.courseId == id }.map { it.id }.toSet())
        if (phase == "denied") {
            assertFalse(ReminderScheduler.allowed(context))
            repository.synchronize()
            assertNotNull(repository.getCourseDetails(id))
        }
        if (phase == "clicked_skipped") {
            assertTrue(repository.observeSessions().first().any {
                it.courseId == id && it.result == SessionResult.SKIPPED
            })
        }
        if (phase == null || phase == "cleanup") {
            repository.deleteCourse(id)
            ReminderScheduler.reconcile(context, repository)
            preferences.edit().clear().commit()
        }
    }
}
