package com.example.voicemail

import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Gets a Gmail access token. Shows Google's consent screen only the first time. */
class GmailAuth(private val activity: ComponentActivity) {

    private var cont: CancellableContinuation<AuthorizationResult>? = null

    private val launcher = activity.registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { res ->
        val c = cont
        cont = null
        try {
            c?.resume(Identity.getAuthorizationClient(activity).getAuthorizationResultFromIntent(res.data))
        } catch (e: Exception) {
            c?.resumeWithException(e)
        }
    }

    suspend fun token(): String {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(
                listOf(
                    Scope("https://www.googleapis.com/auth/gmail.modify"),
                    Scope("https://www.googleapis.com/auth/gmail.send")
                )
            ).build()
        val first = Identity.getAuthorizationClient(activity).authorize(request).await()
        val result = if (first.hasResolution()) {
            val intentSender = first.pendingIntent!!.intentSender
            suspendCancellableCoroutine<AuthorizationResult> { c ->
                cont = c
                launcher.launch(IntentSenderRequest.Builder(intentSender).build())
            }
        } else first
        return result.accessToken ?: error("No Gmail access token returned")
    }
}
