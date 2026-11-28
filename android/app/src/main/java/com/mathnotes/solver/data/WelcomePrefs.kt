package com.mathnotes.solver.data

import com.mathnotes.solver.ui.onboarding.Onboarding
import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

/** Single process-wide DataStore for lightweight onboarding flags. */
private val Context.welcomeDataStore by preferencesDataStore(name = "welcome")

/**
 * Persists whether the one-time first-launch flow (cover, guide, handwriting setup) is done. The
 * flag picks the launch destination and is cleared only from Settings. `has_seen_welcome` and
 * `has_completed_setup` are the two flags it replaced; they are still read for older installs.
 */
object WelcomePrefs {
    private val SEEN = booleanPreferencesKey("has_seen_welcome")
    private val SETUP_DONE = booleanPreferencesKey("has_completed_setup")

    private val ONBOARDED = booleanPreferencesKey("has_completed_onboarding")

    /**
     * The one flag for the whole first-launch flow (cover, guide, wizard). Read once, blocking,
     * from `MainActivity.onCreate` behind the system splash. Installs from before this flag
     * existed are judged by the two older ones, so an update never shows the guide to someone who
     * had already finished.
     */
    fun hasCompletedOnboardingBlocking(context: Context): Boolean = runBlocking {
        context.welcomeDataStore.data.map { Onboarding.hasCompleted(it[ONBOARDED], it[SEEN] ?: false, it[SETUP_DONE] ?: false) }.first()
    }

    /** True when the user reaches Capture from the wizard; false only from Settings "Show welcome screen again". */
    suspend fun setOnboardingComplete(context: Context, complete: Boolean) {
        context.welcomeDataStore.edit {
            it[ONBOARDED] = complete
            // Keep the older flags in step so a downgrade, or the legacy read above, agrees.
            it[SEEN] = complete
            it[SETUP_DONE] = complete
        }
    }

    // The last backend URL that answered a health check.
    private val LAST_BACKEND = stringPreferencesKey("last_backend_url")

    suspend fun lastBackendUrl(context: Context): String? =
        context.welcomeDataStore.data.map { it[LAST_BACKEND] }.first()

    suspend fun setLastBackendUrl(context: Context, url: String) {
        context.welcomeDataStore.edit { it[LAST_BACKEND] = url }
    }

    // Correction-rule schema. Bump when a change invalidates existing samples/rules; the first run
    // after an upgrade clears them (the pre-line_data page read produced unreliable samples).
    private const val CURRENT_RULES_SCHEMA = 2
    private val RULES_SCHEMA = intPreferencesKey("rules_schema")

    /** True exactly once after upgrading to a new rules schema; records the bump so it won't re-fire. */
    suspend fun consumeRuleResetNeeded(context: Context): Boolean {
        val stored = context.welcomeDataStore.data.map { it[RULES_SCHEMA] ?: 1 }.first()
        if (stored < CURRENT_RULES_SCHEMA) {
            context.welcomeDataStore.edit { it[RULES_SCHEMA] = CURRENT_RULES_SCHEMA }
            return true
        }
        return false
    }
}
