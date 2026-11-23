package com.mathnotes.solver.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

/** Single process-wide DataStore for lightweight onboarding flags. */
private val Context.welcomeDataStore by preferencesDataStore(name = "welcome")

/**
 * Persists whether the user has seen the one-time [WelcomeScreen][com.mathnotes.solver.ui.WelcomeScreen].
 * The flag drives the launch destination (welcome vs. capture) and can be cleared from
 * Settings to re-demo the cover.
 */
object WelcomePrefs {
    private val SEEN = booleanPreferencesKey("has_seen_welcome")
    private val SETUP_DONE = booleanPreferencesKey("has_completed_setup")

    /**
     * Read the flags once, blocking. Called from `MainActivity.onCreate` (behind the system
     * splash) to pick the start destination with no first-frame flicker — a single small
     * disk read, so the brief block is fine here.
     */
    fun hasSeenWelcomeBlocking(context: Context): Boolean = runBlocking {
        context.welcomeDataStore.data.map { it[SEEN] ?: false }.first()
    }

    fun hasCompletedSetupBlocking(context: Context): Boolean = runBlocking {
        context.welcomeDataStore.data.map { it[SETUP_DONE] ?: false }.first()
    }

    suspend fun setSeen(context: Context, seen: Boolean) {
        context.welcomeDataStore.edit { it[SEEN] = seen }
    }

    /** Set once the user finishes (or skips) the handwriting setup wizard; cleared to re-run it. */
    suspend fun setSetupComplete(context: Context, complete: Boolean) {
        context.welcomeDataStore.edit { it[SETUP_DONE] = complete }
    }

    // The last backend URL that answered a health check.
    private val LAST_BACKEND = stringPreferencesKey("last_backend_url")

    suspend fun lastBackendUrl(context: Context): String? =
        context.welcomeDataStore.data.map { it[LAST_BACKEND] }.first()

    suspend fun setLastBackendUrl(context: Context, url: String) {
        context.welcomeDataStore.edit { it[LAST_BACKEND] = url }
    }
}
