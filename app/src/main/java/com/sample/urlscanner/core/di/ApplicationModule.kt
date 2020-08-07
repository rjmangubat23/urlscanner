package com.sample.urlscanner.core.di

import android.content.Context
import com.sample.urlscanner.AndroidApplication
import dagger.Module
import dagger.Provides
import javax.inject.Singleton

@Module
class ApplicationModule(private val application: AndroidApplication) {

    @Provides @Singleton fun provideApplicationContext(): Context = application

}