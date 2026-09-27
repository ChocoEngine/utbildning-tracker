package com.utbildning.tracker.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteConstraintException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.utbildning.tracker.data.TrackerRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SchemaSimplificationMigrationTest {
    @Test fun versionFourPreservesDataAndRemovesRedundantStorage(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "simplification-4-5.db"
        context.deleteDatabase(name)
        val path = context.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        try {
            val schema = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets
                .open("com.utbildning.tracker.data.local.TrackerDatabase/4.json").bufferedReader().use { it.readText() }).getJSONObject("database")
            SQLiteDatabase.openOrCreateDatabase(path, null).use { sqlite ->
                val entities = schema.getJSONArray("entities")
                for (i in 0 until entities.length()) {
                    val entity = entities.getJSONObject(i)
                    val table = entity.getString("tableName")
                    sqlite.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                    val indices = entity.optJSONArray("indices")
                    if (indices != null) for (j in 0 until indices.length()) sqlite.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
                val setup = schema.getJSONArray("setupQueries")
                for (i in 0 until setup.length()) sqlite.execSQL(setup.getString(i))
                sqlite.execSQL("INSERT INTO categories VALUES ('category','C')")
                sqlite.execSQL("INSERT INTO courses VALUES ('active','Active',0,'SCHEDULED',1,2,'category',0,0,NULL,123,1)")
                sqlite.execSQL("INSERT INTO courses VALUES ('paused','Paused',1,'SCHEDULED',3,4,NULL,0,1,NULL,NULL,0)")
                sqlite.execSQL("INSERT INTO courses VALUES ('finished','Finished',0,'UNSCHEDULED',5,6,NULL,1,0,6,NULL,0)")
                sqlite.execSQL("INSERT INTO color_reservations VALUES (0,'active')")
                sqlite.execSQL("INSERT INTO color_reservations VALUES (1,'paused')")
                sqlite.execSQL("INSERT INTO topics VALUES ('done','active',0,'Done',NULL,1,20000)")
                sqlite.execSQL("INSERT INTO topics VALUES ('manual','active',1,'Manual',NULL,1,NULL)")
                sqlite.execSQL("INSERT INTO topics VALUES ('pending','active',2,'Pending',NULL,0,NULL)")
                sqlite.execSQL("INSERT INTO topics VALUES ('deleted','active',3,'Deleted',100,0,NULL)")
                sqlite.execSQL("INSERT INTO topics VALUES ('archivedDone','active',4,'Archived done',100,1,19999)")
                sqlite.execSQL("INSERT INTO schedules VALUES ('active',20000,20030,20020,999)")
                sqlite.execSQL("INSERT INTO schedule_rules VALUES ('active',1,1380,60,1)")
                sqlite.execSQL("INSERT INTO sessions VALUES ('past','active',20000,1380,1,2,60,1,'DONE')")
                sqlite.execSQL("INSERT INTO sessions VALUES ('equal','finished',20001,600,1,2,600,1,'DONE')")
                sqlite.version = 4
            }
            val migrated = TrackerDatabase.open(context, name)
            try {
                val dao = migrated.trackerDao()
                assertEquals(6, migrated.openHelper.readableDatabase.version)
                assertEquals(3, dao.getCourses().size)
                assertFalse(dao.hasExhaustedTopics("active"))
                assertEquals(1L, dao.getCourse("active")!!.createdAt)
                assertEquals(2L, dao.getCourse("active")!!.updatedAt)
                assertEquals("category", dao.getCourse("active")!!.categoryId)
                assertTrue(dao.getCourse("paused")!!.isPaused)
                assertEquals(1, dao.getCourse("paused")!!.colorId)
                assertNull(dao.getCourse("finished")!!.colorId)
                assertEquals(6L, dao.getCourse("finished")!!.completedAt)
                assertNull(dao.getTopic("deleted"))
                assertEquals(4, dao.getTopics("active").size)
                assertEquals(20000L, dao.getTopic("done")!!.completionDate)
                assertNull(dao.getTopic("manual")!!.completionDate)
                assertFalse(dao.getTopic("pending")!!.isCompleted)
                assertEquals(19999L, dao.getTopic("archivedDone")!!.completionDate)
                assertNull(dao.getSchedule("active")!!.generatedThrough)
                assertEquals(20030L, dao.getSchedule("active")!!.endsOn)
                assertEquals(60, dao.getScheduleRules("active").single().endMinute)
                assertEquals(SessionResult.DONE, dao.getSession("past")!!.result)
                assertEquals(600, dao.getSession("equal")!!.endMinute)
                migrated.openHelper.readableDatabase.query("SELECT sql FROM sqlite_master WHERE name = 'index_courses_active_color'").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertTrue(cursor.getString(0).contains("WHERE isCompleted = 0"))
                }
                migrated.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
                try { dao.updateCourse(dao.getCourse("paused")!!.copy(colorId = 0)); fail("Unique active color") }
                catch (_: SQLiteConstraintException) { }
                TrackerRepository(migrated).createCourse("Reuse finished color", 2)
            } finally { migrated.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun freshDatabaseAlsoUsesPartialIndexAndCompletionReleasesColor() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "simplification-fresh.db"
        context.deleteDatabase(name)
        val db = TrackerDatabase.open(context, name)
        try {
            val repo = TrackerRepository(db)
            val first = repo.createCourse("C", 0)
            repo.completeCourse(first.id)
            assertNull(repo.getCourse(first.id)!!.colorId)
            val active = repo.createCourse("New C", 0)
            assertEquals(0, active.colorId)
            db.openHelper.readableDatabase.query("SELECT sql FROM sqlite_master WHERE name = 'index_courses_active_color'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.getString(0).contains("WHERE isCompleted = 0"))
            }
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
