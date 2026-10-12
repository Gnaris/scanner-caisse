package com.belijolie.scanner.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Convertit une exception technique en message compréhensible côté utilisateur.
 * Si on a accès au [Context], on détecte en plus si le Wi-Fi est tombé pour
 * remplacer un message type "connection refused" par "Wi-Fi déconnecté".
 */
fun Throwable.toHumanMessage(context: Context? = null): String {
    val netOk = context?.let { isNetworkConnected(it) } ?: true
    if (!netOk) {
        return "📶 Wi-Fi non connecté. Reconnecte le PDA au réseau et réessaie."
    }
    val cause = this.unwrapCause()
    return when (cause) {
        is UnknownHostException ->
            "Adresse introuvable. Vérifie l'IP dans les paramètres."
        is NoRouteToHostException ->
            "📶 Impossible de joindre l'appareil. Vérifie que ton PC/imprimante est sur le même Wi-Fi."
        is ConnectException ->
            "L'appareil refuse la connexion. Est-il allumé et sur le même réseau ?"
        is SocketTimeoutException ->
            "⏱ Pas de réponse — vérifie le Wi-Fi, l'IP, ou que l'appareil est bien allumé."
        else -> cause.message?.takeIf { it.isNotBlank() } ?: "Erreur réseau inconnue."
    }
}

private fun Throwable.unwrapCause(): Throwable {
    var t: Throwable = this
    while (t.cause != null && t.cause !== t) {
        t = t.cause!!
    }
    return t
}

fun isNetworkConnected(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return false
    val net = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(net) ?: return false
    val hasTransport = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        || caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    return hasTransport && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}
