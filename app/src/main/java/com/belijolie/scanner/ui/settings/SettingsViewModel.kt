package com.belijolie.scanner.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.belijolie.scanner.ScannerApp
import com.belijolie.scanner.data.AppSettings
import com.belijolie.scanner.data.SettingsStore
import com.belijolie.scanner.printer.EscPosPrinter
import com.belijolie.scanner.util.toHumanMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val store: SettingsStore) : ViewModel() {

    val settings: StateFlow<AppSettings> = store.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings.DEFAULT)

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    fun save(settings: AppSettings) {
        viewModelScope.launch {
            store.update(settings)
            _toast.value = "Paramètres enregistrés"
        }
    }

    fun testPrint() {
        viewModelScope.launch {
            val s = store.settings.first()
            val result = runCatching { EscPosPrinter.printTest(s.printerIp, s.printerPort) }
            val ctx = ScannerApp.instance.applicationContext
            _toast.value = if (result.isSuccess) "Test envoyé à ${s.printerIp}:${s.printerPort}"
            else "Échec : ${result.exceptionOrNull()?.toHumanMessage(ctx) ?: "erreur inconnue"}"
        }
    }

    fun clearToast() { _toast.value = null }

    class Factory : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return SettingsViewModel(ScannerApp.instance.settingsStore) as T
        }
    }
}
