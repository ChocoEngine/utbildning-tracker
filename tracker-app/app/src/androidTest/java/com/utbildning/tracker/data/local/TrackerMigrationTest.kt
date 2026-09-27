package com.utbildning.tracker.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TrackerMigrationTest {
    @Test fun versionThreeTransfersOnlyCurrentCompletionsAndDropsOldTables() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-3-4-test.db"
        context.deleteDatabase(name)
        val path = context.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        try {
            val schema = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets
                .open("com.utbildning.tracker.data.local.TrackerDatabase/3.json").bufferedReader().use { it.readText() }).getJSONObject("database")
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
                sqlite.execSQL("INSERT INTO categories VALUES ('cat','Programming')")
                sqlite.execSQL("INSERT INTO courses VALUES ('c','C',0,'SCHEDULED',1,2,'cat',0,0,NULL,NULL,0)")
                sqlite.execSQL("INSERT INTO color_reservations VALUES (0,'c')")
                sqlite.execSQL("INSERT INTO schedules VALUES ('c',20000,20030,20020,1)")
                sqlite.execSQL("INSERT INTO schedule_rules VALUES ('c',1,600,NULL,0)")
                for ((position, id) in listOf("session", "manual", "course", "historyOnly", "pending", "archived").withIndex()) {
                    sqlite.execSQL("INSERT INTO topics VALUES (?, 'c', ?, ?, ?)", arrayOf<Any?>(id, position, id, if (id == "archived") 100 else null))
                }
                sqlite.execSQL("INSERT INTO sessions VALUES ('s','c',20000,600,1,2,NULL,0,'DONE')")
                for (id in listOf("session", "historyOnly", "archived")) {
                    sqlite.execSQL("INSERT INTO session_topic_history VALUES ('s',?,'c',?,1)", arrayOf(id, id))
                }
                sqlite.execSQL("INSERT INTO topic_completions VALUES ('session','c','SESSION',999,'s')")
                sqlite.execSQL("INSERT INTO topic_completions VALUES ('archived','c','SESSION',999,'s')")
                sqlite.execSQL("INSERT INTO topic_completions VALUES ('manual','c','MANUAL',999,NULL)")
                sqlite.execSQL("INSERT INTO topic_completions VALUES ('course','c','COURSE_COMPLETION',999,NULL)")
                sqlite.version = 3
            }
            val migrated = TrackerDatabase.open(context, name)
            try {
                val dao = migrated.trackerDao()
                assertEquals(6, dao.getTopics("c").size)
                for (id in listOf("session", "archived")) {
                    assertTrue(dao.getTopic(id)!!.isCompleted)
                    assertEquals(20000L, dao.getTopic(id)!!.completionDate)
                }
                for (id in listOf("manual", "course")) {
                    assertTrue(dao.getTopic(id)!!.isCompleted)
                    assertNull(dao.getTopic(id)!!.completionDate)
                }
                for (id in listOf("historyOnly", "pending")) {
                    assertFalse(dao.getTopic(id)!!.isCompleted)
                    assertNull(dao.getTopic(id)!!.completionDate)
                }
                assertEquals(listOf(0,1,2,3,4,5), dao.getTopics("c").map { it.position })
                assertEquals("cat", dao.getCourse("c")!!.categoryId)
                assertEquals(20020L, dao.getSchedule("c")!!.generatedThrough)
                assertEquals(1, dao.getScheduleRules("c").size)
                assertEquals(0, dao.getCourse("c")!!.colorId)
                assertEquals(SessionResult.DONE, dao.getSession("s")!!.result)
                migrated.openHelper.readableDatabase.query("SELECT name FROM sqlite_master WHERE name IN ('topic_completions','session_topic_history')").use { assertEquals(0, it.count) }
                migrated.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
            } finally { migrated.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun versionOneFileMigratesAndPreservesCourseTopicAndSchedule() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-1-2-test.db"
        context.deleteDatabase(name)
        val path = context.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        try {
            val schemaText = InstrumentationRegistry.getInstrumentation().context.assets
                .open("tracker-schema-v1.json").bufferedReader().use { it.readText() }
            val schema = JSONObject(schemaText).getJSONObject("database")
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
                sqlite.execSQL("INSERT INTO courses (id,name,colorId,mode,createdAt,updatedAt,categoryId,isCompleted,isPaused,completedAt) VALUES ('c','C',0,'SCHEDULED',1,1,NULL,0,0,NULL)")
                sqlite.execSQL("INSERT INTO topics (id,courseId,position,title,archivedAt) VALUES ('t','c',0,'Массивы',NULL)")
                sqlite.execSQL("INSERT INTO schedules (courseId,startsOn,endsOn,generatedThrough) VALUES ('c',20000,NULL,20090)")
                sqlite.execSQL("INSERT INTO sessions (id,courseId,date,startMinute,courseNameSnapshot,colorIdSnapshot,createdAt,updatedAt,endMinute,endDayOffset,result) VALUES ('s','c',20000,600,'Old',2,1,1,NULL,0,'DONE')")
                sqlite.execSQL("INSERT INTO session_topic_history VALUES ('s','t','c','Массивы',1)")
                sqlite.execSQL("INSERT INTO topic_completions VALUES ('t','c','SESSION',1,'s')")
                sqlite.version = 1
            }
            val migrated = TrackerDatabase.open(context, name)
            try {
                val dao = migrated.trackerDao()
                assertEquals("C", dao.getCourse("c")?.name)
                assertEquals("Массивы", dao.getTopic("t")?.title)
                assertNull(dao.getSchedule("c")?.generatedThrough)
                assertEquals(SessionResult.DONE, dao.getSession("s")!!.result)
                assertEquals("C", dao.getSession("s")!!.courseName)
                assertEquals(0, dao.getSession("s")!!.colorId)
                assertEquals(20000L, dao.getTopic("t")!!.completionDate)
                dao.updateCourse(dao.getCourse("c")!!.copy(name = "New", colorId = 4))
                assertEquals("New", dao.getSession("s")!!.courseName)
                assertEquals(4, dao.getSession("s")!!.colorId)
                assertEquals(6, migrated.openHelper.readableDatabase.version)
            } finally { migrated.close() }
        } finally { context.deleteDatabase(name) }
    }
}
