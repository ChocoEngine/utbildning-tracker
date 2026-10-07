package com.utbildning.tracker.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
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
    @Test fun versionSixAddsIndicesWithoutChangingTablesOrData(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-6-7-test.db"
        context.deleteDatabase(name)
        val path = context.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        try {
            val schema = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets
                .open("com.utbildning.tracker.data.local.TrackerDatabase/6.json").bufferedReader().use { it.readText() }).getJSONObject("database")
            val tables = mutableMapOf<String, Int>()
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
                sqlite.execSQL("DROP INDEX index_courses_active_color")
                sqlite.execSQL("CREATE UNIQUE INDEX index_courses_active_color ON courses(colorId) WHERE isCompleted = 0")
                sqlite.execSQL("INSERT INTO categories VALUES ('cat','Programming')")
                sqlite.execSQL("INSERT INTO courses VALUES ('c','Course',3,11,22,'cat',0,1,NULL)")
                sqlite.execSQL("INSERT INTO courses VALUES ('completed','Finished',NULL,33,44,NULL,1,0,44)")
                sqlite.execSQL("INSERT INTO topics VALUES ('t','c',0,'Arrays',1,20000)")
                sqlite.execSQL("INSERT INTO schedules VALUES ('c',20000,20100,20090)")
                sqlite.execSQL("INSERT INTO schedule_rules VALUES ('c',1,600,660)")
                for ((i, result) in listOf("PENDING", "DONE", "SKIPPED").withIndex()) {
                    sqlite.execSQL("INSERT INTO sessions VALUES (?, 'c', ?, 600, 11, 22, 660, ?)", arrayOf<Any>("s$i", 20000 + i, result))
                }
                sqlite.rawQuery("SELECT name, rootpage FROM sqlite_master WHERE type = 'table'", null).use { cursor ->
                    while (cursor.moveToNext()) tables[cursor.getString(0)] = cursor.getInt(1)
                }
                sqlite.version = 6
            }
            val migrated = TrackerDatabase.open(context, name)
            try {
                val dao = migrated.trackerDao()
                val db = migrated.openHelper.readableDatabase
                assertEquals(8, db.version)
                val migratedTables = mutableMapOf<String, Int>()
                db.query("SELECT name, rootpage FROM sqlite_master WHERE type = 'table'").use { cursor ->
                    while (cursor.moveToNext()) migratedTables[cursor.getString(0)] = cursor.getInt(1)
                }
                assertEquals(tables, migratedTables.filterKeys { it != "backup_state" })
                assertTrue(migratedTables.containsKey("backup_state"))
                assertEquals(listOf(
                    CourseEntity("c", "Course", 3, 11, 22, "cat", isPaused = true),
                    CourseEntity("completed", "Finished", null, 33, 44, isCompleted = true, completedAt = 44),
                ).toSet(), dao.getCourses().toSet())
                assertEquals(TopicEntity("t", "c", 0, "Arrays", true, 20000), dao.getTopic("t"))
                assertEquals(ScheduleEntity("c", 20000, 20100, 20090), dao.getSchedule("c"))
                assertEquals(listOf(ScheduleRuleEntity("c", 1, 600, 660)), dao.getScheduleRules("c"))
                assertEquals(listOf(SessionResult.PENDING, SessionResult.DONE, SessionResult.SKIPPED).mapIndexed { i, result ->
                    SessionRecord("s$i", "c", 20000L + i, 600, 11, 22, 660, result)
                }, dao.getSessions("c").map { it.record() })
                assertSessionIndices(db)
                db.query("SELECT sql FROM sqlite_master WHERE name = 'index_courses_active_color'").use {
                    assertTrue(it.moveToFirst())
                    assertTrue(it.getString(0).contains("WHERE isCompleted = 0"))
                }
                db.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
            } finally { migrated.close() }
            val reopened = TrackerDatabase.open(context, name)
            try { assertEquals(8, reopened.openHelper.readableDatabase.version) } finally { reopened.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun freshVersionEightDatabaseHasSessionIndicesAndRestoreState() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "fresh-7-test.db"
        context.deleteDatabase(name)
        try {
            val db = TrackerDatabase.open(context, name)
            try {
                assertEquals(8, db.openHelper.readableDatabase.version)
                assertSessionIndices(db.openHelper.readableDatabase)
                db.openHelper.readableDatabase.query("SELECT name FROM sqlite_master WHERE name = 'backup_state'").use {
                    assertEquals(1, it.count)
                }
            } finally { db.close() }
        } finally { context.deleteDatabase(name) }
    }

    private fun assertSessionIndices(db: SupportSQLiteDatabase) {
        val indices = mutableMapOf<String, Int>()
        db.query("PRAGMA index_list(sessions)").use { cursor ->
            while (cursor.moveToNext()) indices[cursor.getString(cursor.getColumnIndexOrThrow("name"))] =
                cursor.getInt(cursor.getColumnIndexOrThrow("unique"))
        }
        for ((name, columns) in mapOf(
            "index_sessions_date_startMinute" to listOf("date", "startMinute"),
            "index_sessions_result_date" to listOf("result", "date"),
        )) {
            assertEquals(0, indices[name])
            val actual = mutableListOf<String>()
            db.query("PRAGMA index_info($name)").use { cursor ->
                while (cursor.moveToNext()) actual += cursor.getString(cursor.getColumnIndexOrThrow("name"))
            }
            assertEquals(columns, actual)
        }
    }

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
                assertEquals(8, migrated.openHelper.readableDatabase.version)
            } finally { migrated.close() }
        } finally { context.deleteDatabase(name) }
    }
}
