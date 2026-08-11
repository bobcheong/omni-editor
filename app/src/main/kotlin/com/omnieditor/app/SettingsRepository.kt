package com.omnieditor.app

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * R-35: persists editor, compare and appearance settings via DataStore Preferences.
 * All defaults match the values previously hard-coded in SettingsScreen.
 */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val store: DataStore<Preferences> get() = context.dataStore

    // ── Editor ──────────────────────────────────────────────────────────────

    val wordWrap: Flow<Boolean> = store.data.map { it[Keys.WORD_WRAP] ?: false }
    val showLineNumbers: Flow<Boolean> = store.data.map { it[Keys.LINE_NUMBERS] ?: true }
    val showWhitespace: Flow<Boolean> = store.data.map { it[Keys.SHOW_WHITESPACE] ?: false }
    val tabWidth: Flow<Int> = store.data.map { it[Keys.TAB_WIDTH] ?: 4 }
    val fontSize: Flow<Int> = store.data.map { it[Keys.FONT_SIZE] ?: 14 }

    suspend fun setWordWrap(v: Boolean) = store.edit { it[Keys.WORD_WRAP] = v }
    suspend fun setShowLineNumbers(v: Boolean) = store.edit { it[Keys.LINE_NUMBERS] = v }
    suspend fun setShowWhitespace(v: Boolean) = store.edit { it[Keys.SHOW_WHITESPACE] = v }
    suspend fun setTabWidth(v: Int) = store.edit { it[Keys.TAB_WIDTH] = v }
    suspend fun setFontSize(v: Int) = store.edit { it[Keys.FONT_SIZE] = v }

    // ── Compare ──────────────────────────────────────────────────────────────

    /** "unified" or "split" */
    val defaultLayout: Flow<String> = store.data.map { it[Keys.DEFAULT_LAYOUT] ?: "unified" }
    val syncScroll: Flow<Boolean> = store.data.map { it[Keys.SYNC_SCROLL] ?: true }
    /** "line", "word", or "char" */
    val granularity: Flow<String> = store.data.map { it[Keys.GRANULARITY] ?: "word" }

    suspend fun setDefaultLayout(v: String) = store.edit { it[Keys.DEFAULT_LAYOUT] = v }
    suspend fun setSyncScroll(v: Boolean) = store.edit { it[Keys.SYNC_SCROLL] = v }
    suspend fun setGranularity(v: String) = store.edit { it[Keys.GRANULARITY] = v }

    // ── Appearance ───────────────────────────────────────────────────────────

    /** "system", "light", or "dark" */
    val theme: Flow<String> = store.data.map { it[Keys.THEME] ?: "system" }
    val dynamicColor: Flow<Boolean> = store.data.map { it[Keys.DYNAMIC_COLOR] ?: true }

    suspend fun setTheme(v: String) = store.edit { it[Keys.THEME] = v }
    suspend fun setDynamicColor(v: Boolean) = store.edit { it[Keys.DYNAMIC_COLOR] = v }

    // ── Keys ─────────────────────────────────────────────────────────────────

    private object Keys {
        val WORD_WRAP = booleanPreferencesKey("word_wrap")
        val LINE_NUMBERS = booleanPreferencesKey("line_numbers")
        val SHOW_WHITESPACE = booleanPreferencesKey("show_whitespace")
        val TAB_WIDTH = intPreferencesKey("tab_width")
        val FONT_SIZE = intPreferencesKey("font_size")
        val DEFAULT_LAYOUT = stringPreferencesKey("default_layout")
        val SYNC_SCROLL = booleanPreferencesKey("sync_scroll")
        val GRANULARITY = stringPreferencesKey("granularity")
        val THEME = stringPreferencesKey("theme")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
    }
}
