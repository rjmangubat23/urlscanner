package com.sample.urlscanner.features

import android.content.Context
import android.content.Intent
import com.sample.urlscanner.core.platform.BaseActivity
import com.sample.urlscanner.core.platform.BaseFragment
import com.sample.urlscanner.features.scanner.ScannerFragment

class MainActivity : BaseActivity() {
    companion object {
        fun callingIntent(context: Context) = Intent(context, MainActivity::class.java)
    }

    override fun fragment(): BaseFragment = ScannerFragment()
}
