package com.belijolie.scanner.update

/**
 * Infos extraites d'une Release GitHub de l'app Android. Le tag doit suivre
 * le format `app-vX.Y.Z` ; [version] contient le `X.Y.Z` nettoyé pour
 * comparaison. [apkUrl] pointe vers le premier asset .apk de la release.
 */
data class ReleaseInfo(
    val tagName: String,
    val version: String,
    val name: String?,
    val notes: String?,
    val apkUrl: String,
    val apkSize: Long,
)

/**
 * Machine à états exposée par [UpdateManager]. L'UI (dialog + settings)
 * s'abonne via StateFlow et pilote les transitions via les méthodes du
 * manager.
 */
sealed class UpdateState {
    data object Idle : UpdateState()
    data object Checking : UpdateState()
    data object UpToDate : UpdateState()
    data class Available(val release: ReleaseInfo) : UpdateState()
    data class Downloading(
        val release: ReleaseInfo,
        val progressPct: Int,
    ) : UpdateState()
    data class ReadyToInstall(
        val release: ReleaseInfo,
        val apkPath: String,
    ) : UpdateState()
    data class Error(val message: String) : UpdateState()
}
