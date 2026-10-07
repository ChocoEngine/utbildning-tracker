package com.utbildning.tracker.data

import android.content.Context
import com.utbildning.tracker.data.local.TrackerDatabase
import com.utbildning.tracker.maintenance.RetentionPreferences

/** One database per application process; screens share the same repository. */
object AppContainer {
    @Volatile private var instance: TrackerRepository? = null

    fun repository(context: Context): TrackerRepository = instance ?: synchronized(this) {
        val applicationContext = context.applicationContext
        instance ?: TrackerRepository(
            TrackerDatabase.open(applicationContext),
            retentionPolicy = { RetentionPreferences(applicationContext).policy() },
        ).also {
            instance = it
        }
    }
}
