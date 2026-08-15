package com.lopleec.kotj.backup

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.lopleec.kotj.data.SettingsRepository
import java.util.concurrent.TimeUnit

object DriveBackupScheduler {
    fun configure(context: Context, enabled: Boolean) {
        if (!enabled) {
            // Do not initialize WorkManager just to prove that there is no work. This keeps a
            // never-enabled installation on the same local-only startup path as older releases.
            if (!WorkManager.isInitialized()) return
            WorkManager.getInstance(context).apply {
                cancelUniqueWork(IMMEDIATE_WORK_NAME)
                cancelUniqueWork(CHANGE_WORK_NAME)
                cancelUniqueWork(PERIODIC_WORK_NAME)
            }
            return
        }
        val periodic = PeriodicWorkRequestBuilder<DriveBackupWorker>(6, TimeUnit.HOURS)
            .setConstraints(networkConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(WORK_TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            periodic,
        )
    }

    fun onLocalDataChanged(context: Context) {
        if (!SettingsRepository(context).isGoogleDriveBackupEnabled()) return
        enqueue(context, CHANGE_WORK_NAME, CHANGE_DEBOUNCE_SECONDS)
    }

    fun enqueueNow(context: Context) {
        if (!SettingsRepository(context).isGoogleDriveBackupEnabled()) return
        enqueue(context, IMMEDIATE_WORK_NAME, 0)
    }

    private fun enqueue(context: Context, workName: String, delaySeconds: Long) {
        val request = OneTimeWorkRequestBuilder<DriveBackupWorker>()
            .setConstraints(networkConstraints())
            .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(WORK_TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            workName,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    private fun networkConstraints(): Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    private const val PERIODIC_WORK_NAME = "kotj-google-drive-periodic-backup"
    private const val IMMEDIATE_WORK_NAME = "kotj-google-drive-immediate-backup"
    private const val CHANGE_WORK_NAME = "kotj-google-drive-change-backup"
    private const val WORK_TAG = "kotj-google-drive-backup"
    private const val CHANGE_DEBOUNCE_SECONDS = 30L
}
