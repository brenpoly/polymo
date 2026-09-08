package com.digitalpet.di

import android.app.ActivityManager
import android.content.Context
import androidx.room.Room
import com.digitalpet.data.AppDatabase
import com.digitalpet.data.MessageDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideContext(@ApplicationContext context: Context): Context {
        return context
    }

    @Provides
    @Singleton
    fun provideActivityManager(@ApplicationContext context: Context): ActivityManager {
        return context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    }

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "digital_pet_db"
        ).build()
    }

    @Provides
    fun provideMessageDao(database: AppDatabase): MessageDao {
        return database.messageDao()
    }
}
