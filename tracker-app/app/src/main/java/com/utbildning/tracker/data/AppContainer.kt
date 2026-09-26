package com.utbildning.tracker.data

import android.content.Context
import com.utbildning.tracker.data.local.TrackerDatabase

/** One database per application process; screens share the same repository. */
object AppContainer {
    @Volatile private var instance: TrackerRepository? = null

    fun repository(context: Context): TrackerRepository = instance ?: synchronized(this) {
        instance ?: TrackerRepository(TrackerDatabase.open(context.applicationContext)).also {
            instance = it
        }
    }
}
