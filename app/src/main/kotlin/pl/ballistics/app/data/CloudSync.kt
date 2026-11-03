package pl.ballistics.app.data

import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseApp
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import pl.ballistics.app.R
import com.google.firebase.auth.AuthCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await

sealed interface SyncState {
    /** This build has no Firebase project configured. */
    data object Unavailable : SyncState
    data object SignedOut : SyncState
    data class SignedIn(val email: String?, val error: String? = null) : SyncState
}

/**
 * Keeps all profiles in one Firestore document per user (users/{uid}) holding
 * the same JSON as the export file. Whole-document last-write-wins by
 * [AppData.modifiedAt] is enough for one person with a few phones; Firestore
 * queues writes while offline.
 *
 * [onRemote] gets each cloud copy; `first` is true for the first one after this phone
 * signs in to an account, so the two sides are merged once and not on every start
 * (which would bring back rifles deleted on another phone).
 */
class CloudSync(private val context: Context, private val onRemote: (remote: AppData, first: Boolean) -> Unit) {
    private val available = FirebaseApp.getApps(context).isNotEmpty()
    private val auth get() = FirebaseAuth.getInstance()
    private val db get() = FirebaseFirestore.getInstance()
    private var listener: ListenerRegistration? = null
    /** The account this phone's data was last merged with. */
    private val prefs = context.getSharedPreferences("sync", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow<SyncState>(if (available) SyncState.SignedOut else SyncState.Unavailable)
    val state: StateFlow<SyncState> = _state.asStateFlow()

    init {
        if (available) {
            auth.addAuthStateListener { a ->
                val user = a.currentUser
                listener?.remove()
                listener = null
                if (user == null) {
                    _state.value = SyncState.SignedOut
                } else {
                    _state.value = SyncState.SignedIn(user.email)
                    listen(user.uid)
                }
            }
        }
    }

    private fun listen(uid: String) {
        var first = prefs.getString(KEY_MERGED_UID, null) != uid
        // Metadata changes too, so the server's answer arrives even when it matches the cache.
        listener = db.collection(USERS).document(uid).addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
            if (error != null) {
                _state.value = SyncState.SignedIn(auth.currentUser?.email, error.localizedMessage)
                return@addSnapshotListener
            }
            val remote = snapshot?.getString(FIELD_DATA)?.let { Repository.decode(it) }
            // A copy read from the local cache (e.g. offline) is no proof of what the cloud holds.
            val fromServer = snapshot?.metadata?.isFromCache == false
            if (remote == null) {
                // Upload only when the server says there is no copy yet, never on a cache miss.
                if (fromServer) pendingFirstPush?.invoke()
            } else {
                onRemote(remote, first && fromServer)
            }
            if (fromServer && first) {
                first = false
                prefs.edit().putString(KEY_MERGED_UID, uid).apply()
            }
        }
    }

    /** Called when the signed-in user has no cloud copy yet, so the local data gets uploaded. */
    var pendingFirstPush: (() -> Unit)? = null

    fun push(data: AppData) {
        val uid = if (available) auth.currentUser?.uid else null
        uid ?: return
        db.collection(USERS).document(uid).set(
            mapOf(FIELD_DATA to Repository.encode(data), FIELD_MODIFIED to data.modifiedAt),
            SetOptions.merge(),
        )
    }

    /** Google sign-in through Credential Manager; [activityContext] must be an Activity. */
    suspend fun signIn(activityContext: Context): String? {
        if (!available) return context.getString(R.string.sync_not_configured)
        return try {
            val credential = googleCredential(activityContext) ?: return context.getString(R.string.sync_missing_client_id)
            auth.signInWithCredential(credential).await()
            null
        } catch (e: Exception) {
            e.localizedMessage ?: context.getString(R.string.sync_sign_in_failed)
        }
    }

    /** Asks Credential Manager for a Google ID token; null when the build has no OAuth client id. */
    private suspend fun googleCredential(activityContext: Context): AuthCredential? {
        val clientIdRes = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
        if (clientIdRes == 0) return null
        val option = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(context.getString(clientIdRes))
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val credential = CredentialManager.create(activityContext).getCredential(activityContext, request).credential
        val idToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
        return GoogleAuthProvider.getCredential(idToken, null)
    }

    /**
     * Deletes the signed-in account: the cloud copy of the profiles (users/{uid}) and
     * the Firebase account. Firebase wants a recent sign-in for that, so the user
     * picks their Google account once more. Profiles on this phone stay.
     * Returns null on success, otherwise a message for the user.
     */
    suspend fun deleteAccount(activityContext: Context): String? {
        if (!available) return context.getString(R.string.sync_not_configured)
        val user = auth.currentUser ?: return null
        return try {
            val credential = googleCredential(activityContext) ?: return context.getString(R.string.sync_missing_client_id)
            user.reauthenticate(credential).await()
            // Stop listening first, or the empty document would trigger a fresh upload.
            listener?.remove()
            listener = null
            db.collection(USERS).document(user.uid).delete().await()
            user.delete().await()
            prefs.edit().remove(KEY_MERGED_UID).apply()
            runCatching { CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest()) }
            null
        } catch (e: Exception) {
            // Listen again if the account is still there.
            auth.currentUser?.let { if (listener == null) listen(it.uid) }
            e.localizedMessage ?: context.getString(R.string.delete_account_failed)
        }
    }

    /**
     * Sends [suggestion] to the community database queue. The per-user quota
     * document is updated in the same transaction; `firestore.rules` accept the
     * pair only within the limits (one a minute, [DAILY_LIMIT] a day).
     * Returns null on success, otherwise a message for the user.
     */
    suspend fun suggest(suggestion: BulletSuggestion, appVersion: String): String? {
        if (!available) return context.getString(R.string.sync_not_configured)
        val uid = auth.currentUser?.uid ?: return context.getString(R.string.suggest_sign_in)
        val quotaRef = db.collection(QUOTA).document(uid)
        val submissionRef = db.collection(SUBMISSIONS).document()
        return try {
            db.runTransaction { t ->
                val quota = t.get(quotaRef)
                val dayStart = quota.getTimestamp("dayStart")
                val count = quota.getLong("count") ?: 0L
                val now = Timestamp.now()
                val sameDay = dayStart != null && now.seconds - dayStart.seconds < 24 * 3600
                if (sameDay && count >= DAILY_LIMIT) throw QuotaExceeded()
                quota.getTimestamp("last")?.let { if (now.seconds - it.seconds < 60) throw TooSoon() }
                t.set(
                    quotaRef,
                    mapOf(
                        "last" to FieldValue.serverTimestamp(),
                        "dayStart" to if (sameDay) dayStart!! else FieldValue.serverTimestamp(),
                        "count" to if (sameDay) count + 1 else 1L,
                        "lastId" to submissionRef.id,
                    ),
                )
                t.set(
                    submissionRef,
                    suggestion.toMap() + mapOf(
                        "uid" to uid,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "status" to "new",
                        "app" to appVersion.take(40),
                    ),
                )
            }.await()
            null
        } catch (e: Exception) {
            when (e.cause ?: e) {
                is QuotaExceeded -> context.getString(R.string.suggest_daily_limit, DAILY_LIMIT)
                is TooSoon -> context.getString(R.string.suggest_too_soon)
                else -> e.localizedMessage ?: context.getString(R.string.suggest_failed)
            }
        }
    }

    private class QuotaExceeded : RuntimeException()
    private class TooSoon : RuntimeException()

    suspend fun signOut() {
        if (!available) return
        prefs.edit().remove(KEY_MERGED_UID).apply()
        auth.signOut()
        runCatching { CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest()) }
    }

    private companion object {
        const val USERS = "users"
        const val FIELD_DATA = "data"
        const val FIELD_MODIFIED = "modifiedAt"
        const val SUBMISSIONS = "submissions"
        const val QUOTA = "quota"
        const val DAILY_LIMIT = 10
        const val KEY_MERGED_UID = "mergedUid"
    }
}
