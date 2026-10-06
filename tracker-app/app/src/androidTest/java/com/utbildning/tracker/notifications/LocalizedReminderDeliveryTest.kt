package com.utbildning.tracker.notifications

import android.app.LocaleManager
import android.app.NotificationManager
import android.content.Context
import android.os.LocaleList
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.utbildning.tracker.data.AppContainer
import com.utbildning.tracker.domain.WeeklyRule
import java.time.ZonedDateTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Real system alarms and posted notifications, with no Compose test scheduler. */
class LocalizedReminderDeliveryTest {
    @Test fun realAlarmsUseSelectedLanguageAndPreserveCourseName() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val localeManager = context.getSystemService(LocaleManager::class.java)
        val manager = context.getSystemService(NotificationManager::class.java)
        val repository = AppContainer.repository(context)
        val originalLocales = localeManager.applicationLocales
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        for (command in listOf("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS",
            "appops set --uid ${context.packageName} SCHEDULE_EXACT_ALARM allow")) {
            ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes() }
        }
        assertTrue(ReminderScheduler.allowed(context))
        try {
            for ((language, body, channel) in listOf(
                Triple("ru", "Пора заниматься", "Напоминания о занятиях"),
                Triple("en", "Time to study", "Course reminders"),
            )) {
                localeManager.applicationLocales = LocaleList.forLanguageTags(language)
                val localeDeadline = System.currentTimeMillis() + 10_000
                while (context.resources.configuration.locales[0].language != language && System.currentTimeMillis() < localeDeadline) delay(50)
                assertEquals(language, context.resources.configuration.locales[0].language)
                val course = repository.createCourse("Лекции по C · Arrays", repository.availableColors().first())
                try {
                    // This fixture exercises a today's session. At 23:59 the next
                    // minute belongs to tomorrow and is outside reminder eligibility.
                    var now = ZonedDateTime.now()
                    if (now.plusMinutes(1).toLocalDate() != now.toLocalDate()) {
                        val midnight = now.toLocalDate().plusDays(1).atStartOfDay(now.zone)
                        delay((midnight.toInstant().toEpochMilli() - System.currentTimeMillis()).coerceAtLeast(0))
                        now = ZonedDateTime.now()
                    }
                    val next = now.plusMinutes(1).withSecond(0).withNano(0)
                    repository.saveInitialSchedule(course.id, listOf(WeeklyRule(next.dayOfWeek.value, next.hour * 60 + next.minute)))
                    val session = repository.observeSessions().first().first { it.courseId == course.id }
                    assertEquals(next.toLocalDate().toEpochDay(), session.date)
                    assertTrue("Today's alarm fixture must be eligible", repository.getSessionDetails(session.id)!!.canEdit)
                    ReminderScheduler.reconcile(context, repository)
                    val deadline = next.toInstant().toEpochMilli() + 25_000
                    while (manager.activeNotifications.none { it.tag == session.id } && System.currentTimeMillis() < deadline) delay(100)
                    val posted = manager.activeNotifications.singleOrNull { it.tag == session.id }
                    assertNotNull("Actual $language AlarmManager delivery", posted)
                    assertEquals(course.name, posted!!.notification.extras.getString("android.title"))
                    assertEquals(body, posted.notification.extras.getString("android.text"))
                    assertEquals(channel, manager.getNotificationChannel(posted.notification.channelId).name.toString())
                } finally {
                    repository.deleteCourse(course.id)
                    ReminderScheduler.reconcile(context, repository)
                }
            }
        } finally {
            localeManager.applicationLocales = originalLocales
        }
    }
}
