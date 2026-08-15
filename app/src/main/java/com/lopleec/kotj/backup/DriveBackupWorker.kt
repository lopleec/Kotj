package com.lopleec.kotj.backup

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.lopleec.kotj.data.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection

class DriveBackupWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (!SettingsRepository(applicationContext).isGoogleDriveBackupEnabled()) {
            return@withContext Result.success()
        }
        val engine = DriveBackupEngine(applicationContext)
        val accountEmail = engine.accountEmail() ?: return@withContext Result.success()
        val state = engine.state()
        if (!state.encryptionReady || state.restoreRequired) return@withContext Result.success()

        var accessToken: String? = null
        try {
            val authorization = Identity.getAuthorizationClient(applicationContext)
                .authorize(GoogleDriveAuthorization.authorizationRequest(applicationContext, accountEmail))
                .await()
            if (authorization.hasResolution()) {
                engine.markAuthorizationRequired()
                return@withContext Result.success()
            }
            accessToken = authorization.accessToken
            if (accessToken.isNullOrBlank()) {
                engine.markAuthorizationRequired()
                return@withContext Result.success()
            }
            engine.performBackup(accessToken) { !isStopped }
            Result.success()
        } catch (error: DriveApiException) {
            if (error.statusCode == HttpURLConnection.HTTP_UNAUTHORIZED && !accessToken.isNullOrBlank()) {
                runCatching {
                    Identity.getAuthorizationClient(applicationContext)
                        .clearToken(ClearTokenRequest.builder().setToken(accessToken!!).build())
                        .await()
                }
            }
            when {
                error.statusCode == HttpURLConnection.HTTP_UNAUTHORIZED && runAttemptCount >= MAX_RETRIES -> {
                    engine.markAuthorizationRequired()
                    Result.failure()
                }
                error.statusCode == HttpURLConnection.HTTP_CLIENT_TIMEOUT ||
                    error.statusCode == HTTP_TOO_MANY_REQUESTS ||
                    error.statusCode == HttpURLConnection.HTTP_FORBIDDEN && error.reason in RETRYABLE_REASONS ||
                    error.statusCode in 500..599 -> retryOrFail()
                error.statusCode == HttpURLConnection.HTTP_UNAUTHORIZED -> retryOrFail()
                else -> Result.failure()
            }
        } catch (error: ApiException) {
            when (error.statusCode) {
                CommonStatusCodes.SIGN_IN_REQUIRED -> {
                    engine.markAuthorizationRequired()
                    Result.success()
                }
                CommonStatusCodes.NETWORK_ERROR,
                CommonStatusCodes.TIMEOUT,
                CommonStatusCodes.INTERNAL_ERROR,
                -> retryOrFail()
                else -> Result.failure()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: IOException) {
            retryOrFail()
        } catch (_: Throwable) {
            Result.failure()
        }
    }

    private fun retryOrFail(): Result = if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()

    private companion object {
        const val MAX_RETRIES = 3
        const val HTTP_TOO_MANY_REQUESTS = 429
        val RETRYABLE_REASONS = setOf("rateLimitExceeded", "userRateLimitExceeded")
    }
}
