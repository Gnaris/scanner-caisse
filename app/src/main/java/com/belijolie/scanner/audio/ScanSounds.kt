package com.belijolie.scanner.audio

import android.media.AudioManager
import android.media.ToneGenerator

/**
 * Short feedback tones played after a scan.
 *
 *  - [playFound]      → single short "ACK" beep (produit reconnu)
 *  - [playNotFound]   → double "NACK" beep (produit inconnu)
 *
 * Uses Android's built-in [ToneGenerator]: zero asset files, zero latency,
 * works offline.
 */
object ScanSounds {

    private val tone by lazy {
        ToneGenerator(AudioManager.STREAM_MUSIC, 90)
    }

    fun playFound() {
        runCatching {
            tone.startTone(ToneGenerator.TONE_PROP_ACK, 150)
        }
    }

    fun playNotFound() {
        runCatching {
            tone.startTone(ToneGenerator.TONE_PROP_NACK, 300)
        }
    }
}
