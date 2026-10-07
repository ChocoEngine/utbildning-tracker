package com.utbildning.tracker.maintenance

import android.content.Context
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

data class RetentionPolicy(val days: Int?) {
    init {
        require(days == null || days in MIN_DAYS..MAX_DAYS)
    }

    fun cutoffDate(today: LocalDate): LocalDate? = days?.let { today.minusDays(it.toLong()) }

    fun cutoffDate(clock: Clock, zoneId: ZoneId): LocalDate? =
        cutoffDate(LocalDate.now(clock.withZone(zoneId)))

    fun shouldDelete(sessionDate: LocalDate, today: LocalDate): Boolean =
        cutoffDate(today)?.let(sessionDate::isBefore) == true

    companion object {
        const val DEFAULT_DAYS = 365
        const val MIN_DAYS = 1
        const val MAX_DAYS = 3650

        val Default = RetentionPolicy(DEFAULT_DAYS)
        val Disabled = RetentionPolicy(null)
    }
}

class RetentionPreferences(context: Context) {
    private val preferences = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun policy(): RetentionPolicy {
        if (!preferences.contains(KEY_DAYS)) return RetentionPolicy.Default
        val stored = preferences.getInt(KEY_DAYS, RetentionPolicy.DEFAULT_DAYS)
        return when {
            stored == DISABLED -> RetentionPolicy.Disabled
            stored in RetentionPolicy.MIN_DAYS..RetentionPolicy.MAX_DAYS -> RetentionPolicy(stored)
            else -> RetentionPolicy.Default
        }
    }

    fun save(policy: RetentionPolicy) {
        val stored = policy.days ?: DISABLED
        check(preferences.edit().putInt(KEY_DAYS, stored).commit()) {
            "Could not save calendar retention setting"
        }
    }

    companion object {
        internal const val NAME = "calendar_retention"
        private const val KEY_DAYS = "days"
        private const val DISABLED = -1
    }
}
