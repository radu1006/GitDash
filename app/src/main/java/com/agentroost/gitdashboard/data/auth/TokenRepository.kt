package com.agentroost.gitdashboard.data.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Stores the GitHub personal access token.
 *
 * Implementations must never write the token to logs, analytics or unencrypted storage.
 */
interface TokenRepository {

    /** In-memory view of the stored token; null while signed out. */
    val tokenState: StateFlow<String?>

    /** Reads the persisted token (disk/decryption on IO) and refreshes [tokenState]. */
    suspend fun loadToken(): String?

    /**
     * Synchronous read used by the network interceptor on an OkHttp worker thread.
     * Returns null when no token is stored or secure storage is unavailable.
     */
    fun currentToken(): String?

    /** Persists [token]. Returns false when secure storage rejected the write. */
    suspend fun saveToken(token: String): Boolean

    /** Removes the stored token. Returns false when secure storage is unavailable. */
    suspend fun clearToken(): Boolean

    companion object {
        /** @return the token with only the first and last four characters visible, e.g. `ghp_…1a2b`. */
        fun mask(token: String?): String? {
            if (token.isNullOrBlank()) return null
            return when {
                token.length <= 8 -> "…"
                else -> "${token.take(4)}…${token.takeLast(4)}"
            }
        }
    }
}

/**
 * [TokenRepository] backed by [EncryptedSharedPreferences] (AES-256 master key held in the Android
 * Keystore).
 *
 * If the Keystore-backed preferences cannot be created (for example a device whose keystore entry
 * was invalidated), this implementation fails closed: the token is neither read nor written, and
 * the UI shows that secure storage is unavailable. There is deliberately no plaintext fallback.
 */
class EncryptedTokenRepository(context: Context) : TokenRepository {

    private val appContext: Context = context.applicationContext
    private val _tokenState = MutableStateFlow<String?>(null)
    override val tokenState: StateFlow<String?> = _tokenState.asStateFlow()

    private val preferences: SharedPreferences? by lazy {
        try {
            createEncryptedPreferences()
        } catch (error: Exception) {
            // Log the failure class only - never the token or keystore payload.
            Log.e(TAG, "Encrypted token storage unavailable: ${error.javaClass.simpleName}")
            null
        }
    }

    override fun currentToken(): String? =
        preferences?.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() }

    override suspend fun loadToken(): String? = withContext(Dispatchers.IO) {
        val token = currentToken()
        _tokenState.value = token
        token
    }

    override suspend fun saveToken(token: String): Boolean = withContext(Dispatchers.IO) {
        val store = preferences ?: return@withContext false
        val trimmed = token.trim()
        if (trimmed.isEmpty()) return@withContext false

        val stored = store.edit().putString(KEY_TOKEN, trimmed).commit()
        if (stored) {
            _tokenState.value = trimmed
        }
        stored
    }

    override suspend fun clearToken(): Boolean = withContext(Dispatchers.IO) {
        val store = preferences ?: return@withContext false
        store.edit().remove(KEY_TOKEN).commit()
        _tokenState.value = null
        true
    }

    private fun createEncryptedPreferences(): SharedPreferences {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            appContext,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private companion object {
        const val TAG = "TokenRepository"
        const val FILE_NAME = "git_dashboard_secure_tokens"
        const val KEY_TOKEN = "github_pat"
    }
}
