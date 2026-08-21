package com.chomugiri.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.chomugiri.app.core.AppSettings
import com.chomugiri.app.core.Artifact
import com.chomugiri.app.core.Conversation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "chomugiri")

private val KEY_SETTINGS = stringPreferencesKey("settings")
private val KEY_CONVERSATIONS = stringPreferencesKey("conversations")
private val KEY_ARTIFACTS = stringPreferencesKey("artifacts")

/** Lenient so an older saved payload never bricks the app after an update. */
val AppJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    isLenient = true
}

class Store(private val context: Context) {

    // Every stream below falls back to an empty/default value on a read failure (a corrupted
    // preferences file, a disk I/O error) instead of throwing — an uncaught exception here would
    // otherwise crash the whole app on every single launch, since this collects from init{}.
    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        prefs[KEY_SETTINGS]?.let {
            runCatching { AppJson.decodeFromString(AppSettings.serializer(), it) }.getOrNull()
        } ?: AppSettings()
    }.catch { emit(AppSettings()) }

    val conversations: Flow<List<Conversation>> = context.dataStore.data.map { prefs ->
        prefs[KEY_CONVERSATIONS]?.let {
            runCatching {
                AppJson.decodeFromString(ListSerializer(Conversation.serializer()), it)
            }.getOrNull()
        } ?: emptyList()
    }.catch { emit(emptyList()) }

    val artifacts: Flow<List<Artifact>> = context.dataStore.data.map { prefs ->
        prefs[KEY_ARTIFACTS]?.let {
            runCatching {
                AppJson.decodeFromString(ListSerializer(Artifact.serializer()), it)
            }.getOrNull()
        } ?: emptyList()
    }.catch { emit(emptyList()) }

    suspend fun saveSettings(s: AppSettings) {
        context.dataStore.edit { it[KEY_SETTINGS] = AppJson.encodeToString(AppSettings.serializer(), s) }
    }

    suspend fun saveConversations(list: List<Conversation>) {
        context.dataStore.edit {
            it[KEY_CONVERSATIONS] = AppJson.encodeToString(ListSerializer(Conversation.serializer()), list)
        }
    }

    suspend fun saveArtifacts(list: List<Artifact>) {
        context.dataStore.edit {
            it[KEY_ARTIFACTS] = AppJson.encodeToString(ListSerializer(Artifact.serializer()), list)
        }
    }
}
