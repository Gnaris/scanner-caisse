package com.belijolie.scanner.scanner

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Global bus that forwards every scanned barcode (whatever the source:
 * BroadcastReceiver, hardware key test, manual input) to any UI collecting it.
 */
object ScanEventBus {
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val events: SharedFlow<String> = _events.asSharedFlow()

    fun post(barcode: String) {
        val trimmed = barcode.trim()
        if (trimmed.isNotEmpty()) {
            _events.tryEmit(trimmed)
        }
    }
}
