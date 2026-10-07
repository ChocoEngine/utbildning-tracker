package com.utbildning.tracker.maintenance

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.utbildning.tracker.data.AppContainer
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

internal class CalendarCleanupManager(
    context: Context,
    private val workManager: WorkManager = WorkManager.getInstance(context),
) {
    fun reconcile() {
        val request = PeriodicWorkRequestBuilder<CalendarCleanupWorker>(1, TimeUnit.DAYS).build()
        workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    companion object {
        internal const val WORK_NAME = "daily-calendar-cleanup"
    }
}

class CalendarCleanupWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result = try {
        AppContainer.repository(applicationContext).cleanupOldSessions()
        Result.success()
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (error: Exception) {
        Log.e("Tracker", "Calendar cleanup failed", error)
        Result.retry()
    }
}
