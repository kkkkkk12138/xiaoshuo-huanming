package com.xiaoshuo.yijianhuanming.di

import android.content.Context
import androidx.room.Room
import com.xiaoshuo.yijianhuanming.data.AppDatabase
import com.xiaoshuo.yijianhuanming.data.MIGRATION_1_2
import com.xiaoshuo.yijianhuanming.data.ReaderSessionDao
import com.xiaoshuo.yijianhuanming.data.RoomRuleRepository
import com.xiaoshuo.yijianhuanming.data.RuleDao
import com.xiaoshuo.yijianhuanming.data.RuleRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {
    @Binds
    @Singleton
    abstract fun bindRuleRepository(repository: RoomRuleRepository): RuleRepository

    companion object {
        @Provides
        @Singleton
        fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "name-replacer.db")
                .addMigrations(MIGRATION_1_2)
                .build()

        @Provides
        fun provideRuleDao(database: AppDatabase): RuleDao = database.ruleDao()

        @Provides
        fun provideReaderSessionDao(database: AppDatabase): ReaderSessionDao =
            database.readerSessionDao()
    }
}
