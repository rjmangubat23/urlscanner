package com.sample.urlscanner.core.navigation

import android.content.Context
import android.view.View
import com.sample.urlscanner.features.MainActivity
import javax.inject.Inject
import javax.inject.Singleton


@Singleton
class Navigator
@Inject constructor() {

    fun showMain(context: Context) = context.startActivity(MainActivity.callingIntent(context))

    class Extras(val transitionSharedElement: View)
}
