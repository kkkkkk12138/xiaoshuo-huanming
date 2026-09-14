package com.xiaoshuo.yijianhuanming.data

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseTest {
    @get:Rule
    val migration = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    private lateinit var database: AppDatabase

    @Before
    fun createDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun replaceAll_is_atomic_when_insert_fails() = runBlocking {
        val dao = database.ruleDao()
        dao.replaceAll(listOf(RuleEntity("old", "旧名", "新名", 0)))

        val failure = runCatching {
            dao.replaceAll(
                listOf(
                    RuleEntity("duplicate", "甲", "乙", 0),
                    RuleEntity("duplicate", "丙", "丁", 1),
                ),
            )
        }.exceptionOrNull()

        requireNotNull(failure)
        assertEquals(listOf("old"), dao.observeAll().first().map { it.id })
    }

    @Test
    fun replaceAll_accepts_empty_rules() = runBlocking {
        val dao = database.ruleDao()
        dao.replaceAll(listOf(RuleEntity("old", "旧名", "新名", 0)))

        dao.replaceAll(emptyList())

        assertTrue(dao.observeAll().first().isEmpty())
    }

    @Test
    fun rules_follow_ui_position_not_runtime_match_length() = runBlocking {
        val dao = database.ruleDao()
        dao.replaceAll(
            listOf(
                RuleEntity("long", "沈清辞", "B", 1),
                RuleEntity("short", "沈清", "A", 0),
            ),
        )

        assertEquals(listOf("short", "long"), dao.observeAll().first().map { it.id })
    }

    @Test
    fun reader_sessions_keep_location_and_sort_most_recent_first() = runBlocking {
        val dao = database.readerSessionDao()
        dao.upsert(
            ReaderSessionEntity(
                sourceId = "older",
                type = "EPUB",
                title = "旧书",
                uri = "content://books/older.epub",
                chapterId = "chapter-7",
                scrollRatio = 0.42,
                lastOpenedAt = 100,
            ),
        )
        dao.upsert(
            ReaderSessionEntity(
                sourceId = "newer",
                type = "TXT",
                title = "新书",
                uri = "content://books/newer.txt",
                chapterId = null,
                scrollRatio = 0.75,
                lastOpenedAt = 200,
            ),
        )

        val sessions = dao.observeRecent().first()

        assertEquals(listOf("newer", "older"), sessions.map { it.sourceId })
        assertEquals("chapter-7", sessions[1].chapterId)
        assertEquals(0.42, sessions[1].scrollRatio, 0.0)
    }

    @Test
    fun migration_1_2_preserves_rules_and_history_and_adds_nullable_txt_offsets() {
        migration.createDatabase("migration-test", 1).apply {
            execSQL("""INSERT INTO rules VALUES ('r1','宝宝','林晚',0)""")
            execSQL(
                """INSERT INTO reader_sessions
                   (sourceId,type,title,uri,chapterId,scrollRatio,lastOpenedAt)
                   VALUES ('txt','TXT','小说.txt','content://txt',NULL,0.37,100)""",
            )
            execSQL(
                """INSERT INTO reader_sessions
                   (sourceId,type,title,uri,chapterId,scrollRatio,lastOpenedAt)
                   VALUES ('epub','EPUB','书','content://epub','c3',0.4,200)""",
            )
            close()
        }

        migration.runMigrationsAndValidate("migration-test", 2, true, MIGRATION_1_2).use { db ->
            db.query("SELECT id FROM rules").use {
                assertTrue(it.moveToFirst())
                assertEquals("r1", it.getString(0))
            }
            db.query(
                "SELECT sourceId,textOffset,textTotalAtSave FROM reader_sessions ORDER BY sourceId",
            ).use {
                assertTrue(it.moveToFirst())
                assertEquals("epub", it.getString(0))
                assertTrue(it.isNull(1))
                assertTrue(it.isNull(2))
                assertTrue(it.moveToNext())
                assertEquals("txt", it.getString(0))
                assertTrue(it.isNull(1))
                assertTrue(it.isNull(2))
            }
        }
    }

    @Test
    fun metadata_update_preserves_progress_and_save_progress_preserves_metadata() = runBlocking {
        val dao = database.readerSessionDao()
        dao.upsert(
            ReaderSessionEntity(
                sourceId = "txt",
                type = "TXT",
                title = "旧标题",
                uri = "content://old",
                chapterId = null,
                scrollRatio = 0.37,
                lastOpenedAt = 100,
                textOffset = 3700,
                textTotalAtSave = 10_000,
            ),
        )

        dao.upsertMetadataPreservingProgress(
            sourceId = "txt",
            type = "TXT",
            title = "小说.txt",
            uri = "content://txt",
            lastOpenedAt = 200,
        )
        var saved = requireNotNull(dao.findBySourceId("txt"))
        assertEquals("小说.txt", saved.title)
        assertEquals("content://txt", saved.uri)
        assertEquals(0.37, saved.scrollRatio, 0.0)
        assertEquals(3700L, saved.textOffset)
        assertEquals(10_000L, saved.textTotalAtSave)

        dao.saveProgress(
            sourceId = "txt",
            chapterId = null,
            scrollRatio = 0.5,
            textOffset = 5000,
            textTotalAtSave = 10_000,
            lastOpenedAt = 300,
        )
        saved = requireNotNull(dao.findBySourceId("txt"))
        assertEquals("小说.txt", saved.title)
        assertEquals("content://txt", saved.uri)
        assertEquals(0.5, saved.scrollRatio, 0.0)
        assertEquals(5000L, saved.textOffset)
        assertEquals(10_000L, saved.textTotalAtSave)
        assertEquals(300L, saved.lastOpenedAt)
    }
}
