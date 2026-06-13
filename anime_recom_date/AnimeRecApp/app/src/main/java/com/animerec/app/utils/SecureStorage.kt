/*
 * AnimeRec - Anime Recommendation App
 * Copyright (C) 2025 Shuvam Banerji Seal
 *
 * Developed by: Shuvam Banerji Seal
 * GitHub: https://github.com/technicallittlemaster
 *
 * This file is part of AnimeRec.
 * Licensed under the MIT License.
 */
package com.animerec.app.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Secure storage for sensitive data like tokens.
 *
 * IMPORTANT: This class MUST NOT silently fall back to plain SharedPreferences
 * on init failure — that would expose OAuth access/refresh tokens in cleartext
 * on disk. If EncryptedSharedPreferences cannot be created, this class throws
 * so the caller can either surface the error to the user or wipe the file.
 */
class SecureStorage(context: Context) {

    private val TAG = "SecureStorage"

    companion object {
        const val ACCESS_TOKEN_KEY = "access_token"
        const val REFRESH_TOKEN_KEY = "refresh_token"
        const val TOKEN_EXPIRY_KEY = "token_expiry"
        const val CODE_VERIFIER_KEY = "code_verifier"
        private const val PREFS_NAME = "secure_prefs"
    }

    private val prefs: SharedPreferences

    /**
     * True iff the underlying storage is EncryptedSharedPreferences. Use this
     * to fail loudly in any code path that depends on encryption (e.g. token
     * storage) when the device cannot provide it.
     */
    var isEncrypted: Boolean = false
        private set

    init {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        prefs = try {
            val created = EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            isEncrypted = true
            created
        } catch (e: Exception) {
            // Do NOT fall back to plain SharedPreferences — that would store
            // OAuth tokens in cleartext. Wipe any stale plain file (from a
            // previous broken build) and re-throw so the caller can decide.
            Log.e(TAG, "EncryptedSharedPreferences init failed; refusing to fall back to plain prefs", e)
            try {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .clear()
                    .apply()
            } catch (clearError: Exception) {
                Log.e(TAG, "Failed to wipe stale plain prefs", clearError)
            }
            isEncrypted = false
            throw SecureStorageUnavailableException(
                "Cannot initialize encrypted storage on this device. " +
                    "Tokens cannot be saved securely; please restart the app or reinstall.",
                e
            )
        }
    }

    fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    fun getString(key: String, defaultValue: String = ""): String {
        return prefs.getString(key, defaultValue) ?: defaultValue
    }

    fun putLong(key: String, value: Long) {
        prefs.edit().putLong(key, value).apply()
    }

    fun getLong(key: String, defaultValue: Long = 0L): Long {
        return prefs.getLong(key, defaultValue)
    }

    fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    fun getBoolean(key: String, defaultValue: Boolean = false): Boolean {
        return prefs.getBoolean(key, defaultValue)
    }

    fun contains(key: String): Boolean {
        return prefs.contains(key)
    }

    fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}

/**
 * Thrown by [SecureStorage] when the underlying encrypted backing cannot be
 * created. Callers should NOT swallow this — surface it to the user (e.g.,
 * "Secure storage is unavailable on this device") or trigger a re-login.
 */
class SecureStorageUnavailableException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)

