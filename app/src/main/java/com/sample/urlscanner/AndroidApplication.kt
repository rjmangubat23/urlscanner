package com.sample.urlscanner

import android.app.Application
import com.sample.urlscanner.core.di.ApplicationComponent
import com.sample.urlscanner.core.di.ApplicationModule
import com.sample.urlscanner.core.di.DaggerApplicationComponent

class AndroidApplication : Application() {

    val appComponent: ApplicationComponent by lazy(mode = LazyThreadSafetyMode.NONE) {
        DaggerApplicationComponent
                .builder()
                .applicationModule(ApplicationModule(this))
                .build()
    }

    override fun onCreate() {
        super.onCreate()
        this.injectMembers()
    }

    private fun injectMembers() = appComponent.inject(this)
}
