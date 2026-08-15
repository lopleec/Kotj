package com.lopleec.kotj

import android.app.Application
import android.util.Log
import androidx.work.Configuration

/** WorkManager is initialized on demand only after Google Drive backup is enabled. */
class KotjApplication : Application(), Configuration.Provider {
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) Log.INFO else Log.WARN)
            .build()
}
