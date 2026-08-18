package com.omnieditor.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * R-35: ViewModel that exposes DataStore-backed settings as StateFlows and
 * provides write helpers called by SettingsScreen.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repo: SettingsRepository,
) : ViewModel() {

    private fun <T> flow(flow: kotlinx.coroutines.flow.Flow<T>, initial: T) =
        flow.stateIn(viewModelScope, SharingStarted.Eagerly, initial)

    // ── Editor ──────────────────────────────────────────────────────────────

    val wordWrap = flow(repo.wordWrap, false)
    val showLineNumbers = flow(repo.showLineNumbers, true)
    val showWhitespace = flow(repo.showWhitespace, false)
    val tabWidth = flow(repo.tabWidth, 4)
    val fontSize = flow(repo.fontSize, 14)

    fun setWordWrap(v: Boolean) = viewModelScope.launch { repo.setWordWrap(v) }
    fun setShowLineNumbers(v: Boolean) = viewModelScope.launch { repo.setShowLineNumbers(v) }
    fun setShowWhitespace(v: Boolean) = viewModelScope.launch { repo.setShowWhitespace(v) }
    fun setTabWidth(v: Int) = viewModelScope.launch { repo.setTabWidth(v) }
    fun setFontSize(v: Int) = viewModelScope.launch { repo.setFontSize(v) }

    // ── Compare ──────────────────────────────────────────────────────────────

    val defaultLayout = flow(repo.defaultLayout, "unified")
    val syncScroll = flow(repo.syncScroll, true)
    val granularity = flow(repo.granularity, "word")

    fun setDefaultLayout(v: String) = viewModelScope.launch { repo.setDefaultLayout(v) }
    fun setSyncScroll(v: Boolean) = viewModelScope.launch { repo.setSyncScroll(v) }
    fun setGranularity(v: String) = viewModelScope.launch { repo.setGranularity(v) }

    // ── Appearance ───────────────────────────────────────────────────────────

    val theme = flow(repo.theme, "system")
    val dynamicColor = flow(repo.dynamicColor, true)

    fun setTheme(v: String) = viewModelScope.launch { repo.setTheme(v) }
    fun setDynamicColor(v: Boolean) = viewModelScope.launch { repo.setDynamicColor(v) }
}
