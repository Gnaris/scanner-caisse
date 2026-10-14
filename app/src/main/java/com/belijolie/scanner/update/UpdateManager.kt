package com.belijolie.scanner.update

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.belijolie.scanner.BuildConfig
import com.belijolie.scanner.util.toHumanMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Pilote la mise à jour auto de l'app Android depuis GitHub Releases.
 *
 * Workflow :
 *  1. [checkForUpdate] interroge l'API GitHub, filtre les releases dont le tag
 *     commence par `app-v`, compare la plus récente à [BuildConfig.VERSION_NAME].
 *  2. Si une version plus récente existe → état [UpdateState.Available].
 *  3. [download] récupère l'APK dans `cacheDir/updates/`, émet des états
 *     [UpdateState.Downloading] avec pourcentage.
 *  4. Une fois téléchargé → [UpdateState.ReadyToInstall]. L'UI appelle
 *     [install] qui lance l'intent système ACTION_VIEW sur l'APK. Android
 *     affiche son propre prompt "Voulez-vous installer la mise à jour ?".
 *
 * Détection "unknown sources" : si [android.content.pm.PackageManager.canRequestPackageInstalls]
 * renvoie false, [install] ouvre d'abord [Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES]
 * pour que l'utilisateur accorde la permission (une seule fois).
 */
class UpdateManager(private val app: Application) {

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var currentJob: Job? = null

    private val json = Json { ignoreUnknownKeys = true }

    val currentVersion: String get() = BuildConfig.VERSION_NAME

    /**
     * Lance un check contre GitHub. Si [silent] = true, ne passe PAS en état
     * [UpdateState.UpToDate] ni [UpdateState.Error] en cas d'absence de MAJ
     * ou d'erreur réseau — reste [UpdateState.Idle]. Utile pour le check
     * automatique au démarrage (ne doit pas spammer de dialog pour rien).
     */
    fun checkForUpdate(silent: Boolean = false) {
        if (!updaterEnabled()) {
            Log.d(TAG, "Updater désactivé (debug build ou tag placeholder)")
            if (!silent) _state.value = UpdateState.Error("Updater désactivé en debug.")
            return
        }
        currentJob?.cancel()
        currentJob = scope.launch {
            if (!silent) _state.value = UpdateState.Checking
            try {
                val release = fetchLatestAppRelease()
                if (release == null) {
                    if (!silent) _state.value = UpdateState.UpToDate
                    else _state.value = UpdateState.Idle
                    return@launch
                }
                if (isNewer(release.version, currentVersion)) {
                    _state.value = UpdateState.Available(release)
                } else {
                    if (!silent) _state.value = UpdateState.UpToDate
                    else _state.value = UpdateState.Idle
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Check MAJ KO", t)
                if (!silent) {
                    _state.value = UpdateState.Error(t.toHumanMessage(app))
                } else {
                    _state.value = UpdateState.Idle
                }
            }
        }
    }

    /**
     * Télécharge l'APK de [release] dans `cacheDir/updates/`. Émet des états
     * [UpdateState.Downloading] régulièrement, puis [UpdateState.ReadyToInstall].
     */
    fun download(release: ReleaseInfo) {
        currentJob?.cancel()
        currentJob = scope.launch {
            _state.value = UpdateState.Downloading(release, 0)
            try {
                val apk = downloadApk(release)
                _state.value = UpdateState.ReadyToInstall(release, apk.absolutePath)
            } catch (t: Throwable) {
                Log.w(TAG, "Download MAJ KO", t)
                _state.value = UpdateState.Error(t.toHumanMessage(app))
            }
        }
    }

    /**
     * Lance l'install système sur l'APK téléchargé. Si l'utilisateur n'a pas
     * encore accordé la permission "sources inconnues", l'ouvre d'abord dans
     * les paramètres système et revient — l'utilisateur devra retaper
     * "Installer" après avoir activé la permission.
     */
    fun install(context: Context, apkPath: String) {
        val apk = File(apkPath)
        if (!apk.exists()) {
            _state.value = UpdateState.Error("Fichier APK introuvable : ${apk.name}")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            && !context.packageManager.canRequestPackageInstalls()
        ) {
            val settingsIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(settingsIntent) }
            return
        }

        val authority = "${context.packageName}.updateprovider"
        val uri = FileProvider.getUriForFile(context, authority, apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { context.startActivity(intent) }
            .onFailure { _state.value = UpdateState.Error(it.toHumanMessage(app)) }
    }

    /** Revient à l'état Idle (ex : dismiss du dialog). */
    fun reset() {
        currentJob?.cancel()
        _state.value = UpdateState.Idle
    }

    // ---------- Internals ----------

    private fun updaterEnabled(): Boolean {
        if (BuildConfig.GITHUB_REPO.isBlank()) return false
        // En debug on autorise quand même le check : le fallback en signature
        // debug fait qu'un APK produit en CI ne s'installera pas par-dessus
        // un build debug local, donc l'utilisateur verra le prompt échouer
        // mais ça n'empêche pas de tester le flow.
        return true
    }

    private suspend fun fetchLatestAppRelease(): ReleaseInfo? = withContext(Dispatchers.IO) {
        val url = URL("https://api.github.com/repos/${BuildConfig.GITHUB_REPO}/releases?per_page=20")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "ScannerCaisse-Android-Updater")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw RuntimeException("GitHub API HTTP $code")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val releases = json.decodeFromString<List<GhRelease>>(body)
            releases
                .asSequence()
                .filter { !it.draft && !it.prerelease }
                .filter { it.tagName.startsWith(APP_TAG_PREFIX) }
                .mapNotNull { r ->
                    val apkAsset = r.assets.firstOrNull { it.name.endsWith(".apk") }
                        ?: return@mapNotNull null
                    ReleaseInfo(
                        tagName = r.tagName,
                        version = r.tagName.removePrefix(APP_TAG_PREFIX),
                        name = r.name,
                        notes = r.body,
                        apkUrl = apkAsset.url,
                        apkSize = apkAsset.size,
                    )
                }
                .sortedWith(releaseComparatorDescending)
                .firstOrNull()
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun downloadApk(release: ReleaseInfo): File = withContext(Dispatchers.IO) {
        val dir = File(app.cacheDir, "updates").apply { mkdirs() }
        // Purge les vieux APK avant d'en télécharger un nouveau.
        dir.listFiles()?.forEach { if (it.extension == "apk") it.delete() }

        val target = File(dir, "scanner-caisse-${release.version}.apk")
        val url = URL(release.apkUrl)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "ScannerCaisse-Android-Updater")
            instanceFollowRedirects = true
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw RuntimeException("Download HTTP $code")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.apkSize
            var downloaded = 0L
            var lastEmit = -1
            conn.inputStream.use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (total > 0) {
                            val pct = ((downloaded * 100) / total).toInt().coerceIn(0, 100)
                            if (pct != lastEmit) {
                                lastEmit = pct
                                _state.value = UpdateState.Downloading(release, pct)
                            }
                        }
                    }
                }
            }
            target
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Compare deux versions sémantiques "X.Y.Z". Les suffixes (-rc, -dev) sont
     * ignorés pour le tri numérique mais une version avec suffixe est considérée
     * strictement inférieure à la version "clean" correspondante.
     */
    private fun isNewer(remote: String, current: String): Boolean {
        val r = parseVersion(remote)
        val c = parseVersion(current)
        for (i in 0 until maxOf(r.size, c.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = c.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    private fun parseVersion(v: String): List<Int> {
        val clean = v.substringBefore('-').trim()
        return clean.split('.').mapNotNull { it.toIntOrNull() }
    }

    /** Trie les ReleaseInfo par version décroissante (la plus récente d'abord). */
    private val releaseComparatorDescending: Comparator<ReleaseInfo> =
        Comparator { a, b ->
            val va = parseVersion(a.version)
            val vb = parseVersion(b.version)
            var cmp = 0
            for (i in 0 until maxOf(va.size, vb.size)) {
                val ai = va.getOrElse(i) { 0 }
                val bi = vb.getOrElse(i) { 0 }
                if (ai != bi) { cmp = bi - ai; break }
            }
            cmp
        }

    @Serializable
    private data class GhRelease(
        @SerialName("tag_name") val tagName: String,
        val name: String? = null,
        val body: String? = null,
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        val assets: List<GhAsset> = emptyList(),
    )

    @Serializable
    private data class GhAsset(
        val name: String,
        @SerialName("browser_download_url") val url: String,
        val size: Long = 0,
    )

    companion object {
        private const val TAG = "UpdateManager"
        private const val APP_TAG_PREFIX = "app-v"
    }
}
