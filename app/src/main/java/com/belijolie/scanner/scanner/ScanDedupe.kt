package com.belijolie.scanner.scanner

import java.util.concurrent.ConcurrentHashMap

/**
 * Dédup des scans arrivant simultanément à plusieurs collectors de
 * [ScanEventBus].
 *
 * Problème : Navigation Compose garde les ViewModels de l'écran précédent
 * en backstack. [com.belijolie.scanner.ui.checkout.CheckoutViewModel] démarre
 * un `collect` sur [ScanEventBus] dans son init. Résultat : quand on est
 * sur le détail d'un canal, le VM de la liste des canaux est toujours vivant
 * et collecte aussi — chaque scan arrive dans 2 handleScan parallèles →
 * +2 dans le panier au lieu de +1.
 *
 * Fix : avant d'agir sur un scan, chaque collector "claim" le barcode via
 * un cache partagé. Si le même barcode a été claim il y a < [WINDOW_MS], le
 * suivant skip. L'écart entre deux handleScan parallèles est de l'ordre de
 * la ms ; la fenêtre couvre largement et laisse le temps à un rescan
 * volontaire du même produit après l'avoir replacé.
 */
object ScanDedupe {
    private const val WINDOW_MS = 300L
    private val recent = ConcurrentHashMap<String, Long>()

    @Synchronized
    fun claim(barcode: String): Boolean {
        val now = System.currentTimeMillis()
        val prev = recent[barcode]
        if (prev != null && now - prev < WINDOW_MS) return false
        recent[barcode] = now
        // Cleanup opportuniste pour que la map ne grossisse pas indéfiniment.
        if (recent.size > 32) {
            recent.entries.removeIf { now - it.value > WINDOW_MS * 4 }
        }
        return true
    }
}
