package com.xiaoshuo.yijianhuanming.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [RuleEntity::class, ReaderSessionEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun ruleDao(): RuleDao

    abstract fun readerSessionDao(): ReaderSessionDao
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE reader_sessions ADD COLUMN textOffset INTEGER")
        db.execSQL("ALTER TABLE reader_sessions ADD COLUMN textTotalAtSave INTEGER")
    }
}
