package com.utbildning.tracker.notifications

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.utbildning.tracker.data.AppContainer
import com.utbildning.tracker.data.TrackerRepository
import com.utbildning.tracker.data.local.SessionEntity
import com.utbildning.tracker.data.local.SessionResult
import com.utbildning.tracker.data.local.TrackerDatabase
import com.utbildning.tracker.domain.WeeklyRule
import java.time.LocalDate
import java.time.ZonedDateTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OvernightAlarmDeliveryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val repository = AppContainer.repository(context)
    private val manager = context.getSystemService(NotificationManager::class.java)

    @Test fun realAlarmCrossesMidnightForExplicitEndAndNinetyMinuteFallback() = runBlocking {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        listOf(
            "pm grant ${context.packageName} ${Manifest.permission.POST_NOTIFICATIONS}",
            "appops set --uid ${context.packageName} SCHEDULE_EXACT_ALARM allow",
        ).forEach { command ->
            ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes() }
        }
        assertTrue("Notification and exact-alarm grants", ReminderScheduler.allowed(context))

        val now = ZonedDateTime.now()
        assumeTrue("Run on the dedicated RTC-shifted AVD near 00:58", now.hour == 0 && now.minute in 57..59)
        val today = now.toLocalDate()
        val yesterday = today.minusDays(1)
        val database = TrackerRepository::class.java.getDeclaredField("database").let { field ->
            field.isAccessible = true
            field.get(repository) as TrackerDatabase
        }
        val dao = database.trackerDao()
        val explicitCourse = repository.createCourse("Night explicit end", repository.availableColors().first())
        val fallbackCourse = repository.createCourse("Night ninety minutes", repository.availableColors().first())
        val explicitId = "night_explicit_${System.currentTimeMillis()}"
        val fallbackId = "night_fallback_${System.currentTimeMillis()}"
        try {
            repository.saveInitialSchedule(explicitCourse.id, listOf(WeeklyRule(today.plusDays(1).dayOfWeek.value, 720)))
            repository.saveInitialSchedule(fallbackCourse.id, listOf(WeeklyRule(today.plusDays(1).dayOfWeek.value, 780)))
            val created = System.currentTimeMillis()
            dao.insertSession(SessionEntity(explicitId, explicitCourse.id, yesterday.toEpochDay(), 23 * 60 + 30,
                explicitCourse.name, explicitCourse.colorId, created, created, endMinute = 60))
            dao.insertSession(SessionEntity(fallbackId, fallbackCourse.id, yesterday.toEpochDay(), 23 * 60 + 30,
                fallbackCourse.name, fallbackCourse.colorId, created, created, endMinute = null))

            repository.synchronize()
            ReminderScheduler.reconcile(context, repository)
            assertEquals(SessionResult.PENDING, dao.getSession(explicitId)!!.result)
            assertEquals(SessionResult.PENDING, dao.getSession(fallbackId)!!.result)

            val alarmAt = today.atTime(1, 0).atZone(now.zone).toInstant().toEpochMilli()
            val deadline = alarmAt + 30_000
            while (System.currentTimeMillis() < deadline &&
                manager.activeNotifications.count { it.tag in setOf(explicitId, fallbackId) } < 2) {
                delay(200)
            }
            val explicitNotification = manager.activeNotifications.singleOrNull { it.tag == explicitId }
            val fallbackNotification = manager.activeNotifications.singleOrNull { it.tag == fallbackId }
            assertNotNull("Real 23:30→01:00 alarm delivery", explicitNotification)
            assertNotNull("Real 23:30+90 minute alarm delivery", fallbackNotification)
            assertEquals(2, explicitNotification!!.notification.actions.size)
            assertEquals(2, fallbackNotification!!.notification.actions.size)

            repository.synchronize()
            ReminderScheduler.reconcile(context, repository)
            assertTrue(manager.activeNotifications.any { it.tag == explicitId })
            assertTrue(manager.activeNotifications.any { it.tag == fallbackId })

            explicitNotification.notification.actions[1].actionIntent.send()
            fallbackNotification.notification.actions[0].actionIntent.send()
            val resultDeadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < resultDeadline &&
                (dao.getSession(explicitId)?.result != SessionResult.SKIPPED ||
                    dao.getSession(fallbackId)?.result != SessionResult.DONE)) {
                delay(100)
            }
            assertEquals(SessionResult.SKIPPED, dao.getSession(explicitId)!!.result)
            assertEquals(SessionResult.DONE, dao.getSession(fallbackId)!!.result)
        } finally {
            runCatching { repository.deleteCourse(explicitCourse.id) }
            runCatching { repository.deleteCourse(fallbackCourse.id) }
            manager.cancelAll()
            ReminderScheduler.reconcile(context, repository)
        }
    }
}
