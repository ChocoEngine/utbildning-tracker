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
                sqlite.version = 1
            }
            val migrated = TrackerDatabase.open(context, name)
            try {
                val dao = migrated.trackerDao()
                assertEquals("C", dao.getCourse("c")?.name)
                assertNull(dao.getCourse("c")?.exhaustedAt)
                assertFalse(dao.getCourse("c")!!.completionPromptDismissed)
                assertEquals("Массивы", dao.getTopic("t")?.title)
                assertEquals(20090L, dao.getSchedule("c")?.generatedThrough)
                assertNull(dao.getSchedule("c")?.generationNotBefore)
                assertEquals(2, migrated.openHelper.readableDatabase.version)
            } finally { migrated.close() }
        } finally { context.deleteDatabase(name) }
    }
}
