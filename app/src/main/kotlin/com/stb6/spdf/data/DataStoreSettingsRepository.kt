package com.stb6.spdf.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.readerSettingsDataStore by preferencesDataStore(
    name = "reader_settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

// Writes must outlive the settings screen.
private val settingsWrites = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

class DataStoreSettingsRepository internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val writeScope: CoroutineScope = settingsWrites,
) {
    constructor(context: Context) : this(context.applicationContext.readerSettingsDataStore)
    val settings: Flow<ReaderSettings> = dataStore.data
        .catch { failure ->
            if (failure is IOException) emit(emptyPreferences()) else throw failure
        }
        .map(::readSettings)

    fun update(transform: (ReaderSettings) -> ReaderSettings): Deferred<ReaderSettings?> = writeScope.async {
        try {
            writeSettings(transform)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: IOException) {
            null
        }
    }

    private suspend fun writeSettings(transform: (ReaderSettings) -> ReaderSettings): ReaderSettings = readSettings(
        dataStore.edit { preferences ->
            val updated = transform(readSettings(preferences))
            preferences[Keys.SCROLL_DIRECTION] = updated.scrollDirection.name
            preferences[Keys.DOC_DARK_TRIGGER] = updated.docDarkTrigger.name
            preferences[Keys.DOC_DARK_STYLE] = updated.docDarkStyle.name
            preferences[Keys.SMART_SKIP_DARK_DOCUMENTS] = updated.smartSkipDarkDocuments
            preferences[Keys.PAGE_LABEL_HALF_SECONDS] = updated.pageLabelHalfSeconds
            preferences[Keys.KEEP_SCREEN_ON] = updated.keepScreenOn
            preferences[Keys.BACK_RESETS_ZOOM] = updated.backResetsZoom
        }
    )

    private fun readSettings(preferences: Preferences): ReaderSettings {
        val defaults = ReaderSettings()
        return ReaderSettings(
            scrollDirection = preferences[Keys.SCROLL_DIRECTION]
                .toEnumOrDefault(defaults.scrollDirection),
            docDarkTrigger = preferences[Keys.DOC_DARK_TRIGGER]
                .toEnumOrDefault(defaults.docDarkTrigger),
            docDarkStyle = preferences[Keys.DOC_DARK_STYLE]
                .toEnumOrDefault(defaults.docDarkStyle),
            smartSkipDarkDocuments = preferences[Keys.SMART_SKIP_DARK_DOCUMENTS]
                ?: defaults.smartSkipDarkDocuments,

            pageLabelHalfSeconds = (preferences[Keys.PAGE_LABEL_HALF_SECONDS] ?: defaults.pageLabelHalfSeconds)
                .coerceIn(PAGE_LABEL_RANGE),
            keepScreenOn = preferences[Keys.KEEP_SCREEN_ON] ?: defaults.keepScreenOn,
            backResetsZoom = preferences[Keys.BACK_RESETS_ZOOM] ?: defaults.backResetsZoom,
        )
    }

    private object Keys {
        val SCROLL_DIRECTION = stringPreferencesKey("scroll_direction")
        val DOC_DARK_TRIGGER = stringPreferencesKey("doc_dark_trigger")
        val DOC_DARK_STYLE = stringPreferencesKey("doc_dark_style")
        val SMART_SKIP_DARK_DOCUMENTS = booleanPreferencesKey("smart_skip_dark_documents")
        val PAGE_LABEL_HALF_SECONDS = intPreferencesKey("page_label_half_seconds")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val BACK_RESETS_ZOOM = booleanPreferencesKey("back_resets_zoom")
    }
}

private inline fun <reified T : Enum<T>> String?.toEnumOrDefault(default: T): T =
    enumValues<T>().firstOrNull { it.name == this } ?: default
