package com.lopleec.kotj.backup

import android.accounts.Account
import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.Scope
import com.lopleec.kotj.R

object GoogleDriveAuthorization {
    const val DRIVE_APPDATA_SCOPE = "https://www.googleapis.com/auth/drive.appdata"
    private const val GOOGLE_ACCOUNT_TYPE = "com.google"

    val scopes: List<Scope> = listOf(Scope(DRIVE_APPDATA_SCOPE))

    fun authorizationRequest(context: Context, accountEmail: String? = null): AuthorizationRequest {
        validateConfiguration(context)
        return AuthorizationRequest.builder()
            .setRequestedScopes(scopes)
            .apply {
                accountEmail?.takeIf(String::isNotBlank)?.let {
                    setAccount(Account(it, GOOGLE_ACCOUNT_TYPE))
                }
            }
            .build()
    }

    fun revokeRequest(context: Context, accountEmail: String): RevokeAccessRequest {
        validateConfiguration(context)
        return RevokeAccessRequest.builder()
            .setAccount(Account(accountEmail, GOOGLE_ACCOUNT_TYPE))
            .setScopes(scopes)
            .build()
    }

    fun validateConfiguration(context: Context) {
        val clientId = context.getString(R.string.google_oauth_client_id)
        val projectId = context.getString(R.string.google_cloud_project_id)
        check(clientId.endsWith(".apps.googleusercontent.com") && clientId.length > 40) {
            "Google Android OAuth client ID is not configured"
        }
        check(projectId.isNotBlank()) { "Google Cloud project ID is not configured" }
    }
}
