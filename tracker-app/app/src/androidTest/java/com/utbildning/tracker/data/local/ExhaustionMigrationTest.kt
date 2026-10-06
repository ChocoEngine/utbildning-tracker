package com.utbildning.tracker.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.utbildning.tracker.data.TrackerRepository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExhaustionMigrationTest {
    @Test fun versionFiveDropsExhaustionWithoutLosingHistoryAndCanResume(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "exhaustion-5-6.db"
        context.deleteDatabase(name)
        val path = context.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        try {
            val schema = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets
                .open("com.utbildning.tracker.data.local.TrackerDatabase/5.json").bufferedReader().use { it.readText() }).getJSONObject("database")
            SQLiteDatabase.openOrCreateDatabase(path, null).use { sqlite ->
                val entities = schema.getJSONArray("entities")
                for (i in 0 until entities.length()) {
                    val entity = entities.getJSONObject(i)
                    val table = entity.getString("tableName")
                    sqlite.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                    val indices = entity.optJSONArray("indices")
                    if (indices != null) for (j in 0 until indices.length()) {
                        sqlite.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                    }
                }
                val setup = schema.getJSONArray("setupQueries")
                for (i in 0 until setup.length()) sqlite.execSQL(setup.getString(i))
                sqlite.execSQL("INSERT INTO categories VALUES ('category','C')")
                // Include all-done topics with a missing legacy flag as well as a stopped course.
                sqlite.execSQL("INSERT INTO courses VALUES ('stopped','C',0,1,2,'category',0,0,NULL,123)")
                sqlite.execSQL("INSERT INTO courses VALUES ('unflagged','Other',1,3,4,NULL,0,0,NULL,NULL)")
                sqlite.execSQL("INSERT INTO courses VALUES ('empty','Practice',2,5,6,NULL,0,0,NULL,NULL)")
                sqlite.execSQL("INSERT INTO topics VALUES ('done','stopped',0,'Arrays',1,20000)")
                sqlite.execSQL("INSERT INTO topics VALUES ('manual','unflagged',0,'Pointers',1,NULL)")
                for (id in listOf("stopped", "unflagged", "empty")) {
                    sqlite.execSQL("INSERT INTO schedules VALUES ('$id',20000,NULL,30000)")
                    sqlite.execSQL("INSERT INTO schedule_rules VALUES ('$id',1,720,NULL)")
                }
                sqlite.execSQL("INSERT INTO sessions VALUES ('past','stopped',20000,720,1,2,NULL,'DONE')")
                sqlite.version = 5
            }
            val db = TrackerDatabase.open(context, name)
            try {
                val dao = db.trackerDao()
                assertEquals(7, db.openHelper.readableDatabase.version)
                db.openHelper.readableDatabase.query("PRAGMA table_info(courses)").use { cursor ->
                    while (cursor.moveToNext()) assertNotEquals("exhaustedAt", cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
                assertEquals(3, dao.getCourses().size)
                assertEquals("category", dao.getCourse("stopped")!!.categoryId)
                assertEquals(2L, dao.getCourse("stopped")!!.updatedAt)
                assertEquals(20000L, dao.getTopic("done")!!.completionDate)
                assertNull(dao.getTopic("manual")!!.completionDate)
                assertEquals(SessionResult.DONE, dao.getSession("past")!!.result)
                assertNull(dao.getSchedule("stopped")!!.generatedThrough)
                assertNull(dao.getSchedule("unflagged")!!.generatedThrough)
                assertEquals(30000L, dao.getSchedule("empty")!!.generatedThrough)
                assertEquals(listOf("empty"), dao.getReminderCourses().map { it.id })
                db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
                val repo = TrackerRepository(db, now = { Instant.parse("2026-12-07T10:00:00Z").toEpochMilli() }, zone = { ZoneId.of("UTC") })
                repo.synchronize()
                assertEquals(1, dao.getSessions("stopped").size)
                repo.toggleTopicCompletion("stopped", "done")
                val generated = dao.getSessions("stopped").filter { it.id != "past" }
                assertTrue(generated.isNotEmpty())
                assertEquals(LocalDate.parse("2026-12-07").toEpochDay(), generated.first().date)
            } finally { db.close() }
        } finally { context.deleteDatabase(name) }
    }
}
