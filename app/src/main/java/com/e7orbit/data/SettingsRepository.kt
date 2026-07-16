package com.e7orbit.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.e7orbit.model.HuntConfig
import com.e7orbit.model.HuntDifficulty
import com.e7orbit.model.HuntDungeon
import com.e7orbit.model.HuntEnergyRefill
import com.e7orbit.model.RunConfig
import com.e7orbit.model.RunSummary
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val Context.orbitDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "e7_orbit_settings",
)

class SettingsRepository(
    context: Context,
) {
    private val dataStore = context.applicationContext.orbitDataStore

    val config: Flow<RunConfig> = dataStore.safeData
        .map { preferences ->
            RunConfig(
                buyCovenantBookmarks = preferences[Keys.BUY_COVENANT] ?: true,
                buyMysticMedals = preferences[Keys.BUY_MYSTIC] ?: true,
                maxRefreshes = preferences[Keys.MAX_REFRESHES] ?: 100,
                matchThreshold = preferences[Keys.MATCH_THRESHOLD] ?: 0.92,
            ).normalized()
        }
        .distinctUntilChanged()

    val huntConfig: Flow<HuntConfig> = dataStore.safeData
        .map { preferences ->
            HuntConfig(
                dungeon = preferences[Keys.HUNT_DUNGEON]
                    ?.let { stored ->
                        runCatching { HuntDungeon.valueOf(stored) }.getOrNull()
                    }
                    ?: HuntDungeon.WYVERN,
                difficulty = preferences[Keys.HUNT_DIFFICULTY]
                    ?.let { stored ->
                        runCatching { HuntDifficulty.valueOf(stored) }.getOrNull()
                    }
                    ?: HuntDifficulty.HELL,
                managedBattle = preferences[Keys.HUNT_MANAGED_BATTLE] ?: true,
                runCount = preferences[Keys.HUNT_RUN_COUNT] ?: 20,
                energyRefill = preferences[Keys.HUNT_ENERGY_REFILL]
                    ?.let { stored ->
                        runCatching { HuntEnergyRefill.valueOf(stored) }.getOrNull()
                    }
                    ?: HuntEnergyRefill.DISABLED,
            ).normalized()
        }
        .distinctUntilChanged()

    val lastSummary: Flow<RunSummary> = dataStore.safeData
        .map { preferences ->
            RunSummary(
                completedRefreshes = preferences[Keys.LAST_REFRESHES] ?: 0,
                shopPagesScanned = preferences[Keys.LAST_PAGES_SCANNED] ?: 0,
                covenantBookmarksBought = preferences[Keys.LAST_COVENANT] ?: 0,
                mysticMedalsBought = preferences[Keys.LAST_MYSTIC] ?: 0,
                goldSpent = preferences[Keys.LAST_GOLD_SPENT] ?: 0L,
                elapsedMs = preferences[Keys.LAST_ELAPSED] ?: 0L,
                stopReason = preferences[Keys.LAST_STOP_REASON] ?: "NONE",
                completedAtEpochMs = preferences[Keys.LAST_COMPLETED_AT] ?: 0L,
            )
        }
        .distinctUntilChanged()

    suspend fun saveConfig(config: RunConfig) {
        val normalized = config.normalized()
        dataStore.edit { preferences ->
            preferences[Keys.BUY_COVENANT] = normalized.buyCovenantBookmarks
            preferences[Keys.BUY_MYSTIC] = normalized.buyMysticMedals
            preferences[Keys.MAX_REFRESHES] = normalized.maxRefreshes
            preferences[Keys.MATCH_THRESHOLD] = normalized.matchThreshold
        }
    }

    suspend fun saveHuntConfig(config: HuntConfig) {
        val normalized = config.normalized()
        dataStore.edit { preferences ->
            preferences[Keys.HUNT_DUNGEON] = normalized.dungeon.name
            preferences[Keys.HUNT_DIFFICULTY] = normalized.difficulty.name
            preferences[Keys.HUNT_MANAGED_BATTLE] = normalized.managedBattle
            preferences[Keys.HUNT_RUN_COUNT] = normalized.runCount
            preferences[Keys.HUNT_ENERGY_REFILL] = normalized.energyRefill.name
        }
    }

    suspend fun saveSummary(summary: RunSummary) {
        dataStore.edit { preferences ->
            preferences[Keys.LAST_REFRESHES] = summary.completedRefreshes
            preferences[Keys.LAST_PAGES_SCANNED] = summary.shopPagesScanned
            preferences[Keys.LAST_COVENANT] = summary.covenantBookmarksBought
            preferences[Keys.LAST_MYSTIC] = summary.mysticMedalsBought
            preferences[Keys.LAST_GOLD_SPENT] = summary.goldSpent
            preferences[Keys.LAST_ELAPSED] = summary.elapsedMs
            preferences[Keys.LAST_STOP_REASON] = summary.stopReason
            preferences[Keys.LAST_COMPLETED_AT] = summary.completedAtEpochMs
        }
    }

    private val DataStore<Preferences>.safeData: Flow<Preferences>
        get() = data.catch { error ->
            if (error is IOException) {
                emit(androidx.datastore.preferences.core.emptyPreferences())
            } else {
                throw error
            }
        }

    private object Keys {
        val BUY_COVENANT = booleanPreferencesKey("buy_covenant")
        val BUY_MYSTIC = booleanPreferencesKey("buy_mystic")
        val MAX_REFRESHES = intPreferencesKey("max_refreshes")
        val MATCH_THRESHOLD = doublePreferencesKey("match_threshold")
        val HUNT_DUNGEON = stringPreferencesKey("hunt_dungeon")
        val HUNT_DIFFICULTY = stringPreferencesKey("hunt_difficulty")
        val HUNT_MANAGED_BATTLE = booleanPreferencesKey("hunt_managed_battle")
        val HUNT_RUN_COUNT = intPreferencesKey("hunt_run_count")
        val HUNT_ENERGY_REFILL = stringPreferencesKey("hunt_energy_refill")
        val LAST_REFRESHES = intPreferencesKey("last_refreshes")
        val LAST_PAGES_SCANNED = intPreferencesKey("last_pages_scanned")
        val LAST_COVENANT = intPreferencesKey("last_covenant")
        val LAST_MYSTIC = intPreferencesKey("last_mystic")
        val LAST_GOLD_SPENT = longPreferencesKey("last_gold_spent")
        val LAST_ELAPSED = longPreferencesKey("last_elapsed")
        val LAST_STOP_REASON = stringPreferencesKey("last_stop_reason")
        val LAST_COMPLETED_AT = longPreferencesKey("last_completed_at")
    }
}
