package com.biplov.kaihatch

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.work.Configuration
import com.biplov.kaihatch.data.LanguageHelper
import java.util.concurrent.Executors

/**
 * Custom Application implementing on-demand [Configuration.Provider] for WorkManager.
 *
 * Why this matters for 120fps startup smoothness:
 * By disabling WorkManager's default ContentProvider in AndroidManifest.xml and providing
 * configuration here, WorkManager's internal SQLite database (WorkDatabase) is NEVER initialized
 * during cold start—it is only initialized on background IO threads when an extraction or
 * compression job is actually launched.
 */
class KaiHatchApplication : Application(), Configuration.Provider {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LanguageHelper.wrapContext(base))
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(Log.ERROR)
            .setExecutor(Executors.newFixedThreadPool(2))
            .build()
}
