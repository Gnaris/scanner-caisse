package com.belijolie.scanner.scanner

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log

/**
 * Listens to the PDA scanner broadcast and forwards the payload to [ScanEventBus].
 *
 * On the Vanquisher RD50T (and most MediaTek-based rugged PDAs), the firmware
 * broadcasts the scanned barcode on action
 * "com.android.serial.BARCODEPORT_RECEIVEDDATA_ACTION".
 *
 * The exact name of the extra holding the string varies: "data", "barocode_string",
 * "barcode_string", "SCAN_BARCODE1"... Instead of guessing, we walk every string
 * extra in the intent and pick the first non-empty one (that is not an obvious
 * metadata key like "length"). This makes the app resilient to firmware quirks.
 */
class ScanReceiver(
    private val preferredExtra: String
) : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val extras = intent.extras ?: return

        // 1) Try the extra the user configured (or the firmware's documented one).
        val preferred = extras.getString(preferredExtra)?.trim()
        if (!preferred.isNullOrEmpty()) {
            Log.d(TAG, "Scan via extra='$preferredExtra' value='$preferred' action='${intent.action}'")
            ScanEventBus.post(preferred)
            return
        }

        // 2) Fall back to a short list of well-known extra keys used by OEMs.
        for (key in KNOWN_EXTRA_KEYS) {
            val value = extras.getString(key)?.trim()
            if (!value.isNullOrEmpty()) {
                Log.d(TAG, "Scan via known extra='$key' value='$value' action='${intent.action}'")
                ScanEventBus.post(value)
                return
            }
        }

        // 3) Last resort: scan every string extra and take the first that looks
        //    like a barcode (non-empty, not an obvious metadata key).
        for (key in extras.keySet()) {
            val value = extras.get(key)
            if (value is String && value.isNotBlank() && key.lowercase() !in METADATA_KEYS) {
                Log.d(TAG, "Scan via unknown extra='$key' value='$value' action='${intent.action}'")
                ScanEventBus.post(value.trim())
                return
            }
        }

        Log.w(TAG, "Scan broadcast received but no string extra found. keys=${extras.keySet()}")
    }

    companion object {
        private const val TAG = "ScanReceiver"

        private val KNOWN_EXTRA_KEYS = listOf(
            "data",
            "barcode",
            "barcode_string",
            "barocode_string",
            "BarocodeStr",
            "SCAN_BARCODE1",
            "scannerdata",
            "scanner_data",
            "scanResult"
        )

        private val METADATA_KEYS = setOf(
            "length", "size", "symbology", "codeid", "aim_id", "type", "charset"
        )

        fun register(context: Context, action: String, extra: String): ScanReceiver {
            val receiver = ScanReceiver(extra)
            val filter = IntentFilter(action)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(receiver, filter)
            }
            return receiver
        }

        fun unregister(context: Context, receiver: ScanReceiver) {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }
}
