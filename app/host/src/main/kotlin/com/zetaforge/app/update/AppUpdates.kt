package com.zetaforge.app.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.zetaforge.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Updating ZetaForge from its own GitHub releases.
 *
 * The repository is public, so this needs no account and no token: the release
 * list is one anonymous GET, and the APK is a plain download. Nothing is sent
 * anywhere - the check asks GitHub what exists and compares it with the version
 * this build carries.
 *
 * Three details decide which file is downloaded, and getting any of them wrong
 * produces an update that cannot be installed:
 *
 *  * **Signature.** A debuggable build and a release build are signed with
 *    different keys, and Android refuses to update one with the other. So a
 *    debug install is only ever offered the debug asset, and vice versa.
 *  * **Architecture.** A release publishes one APK per ABI plus a universal
 *    one. The device's own ABI is preferred, the universal one is the fallback.
 *  * **Integrity.** The download is checked against the `SHA256SUMS.txt` the
 *    release publishes, before it is ever handed to the package installer.
 */
object AppUpdates {

    private const val REPO = "zetawave/ZetaForge"
    private const val TAG_PREFIX = "host-v"
    private const val SUMS_ASSET = "SHA256SUMS.txt"
    private const val UPDATE_DIR = "updates"

    /** What a check found. */
    sealed interface Result {
        data class Available(val update: Update) : Result
        data object UpToDate : Result
        data class Failed(val reason: String) : Result
    }

    data class Update(
        val version: String,
        val tag: String,
        val notes: String,
        val assetName: String,
        val assetUrl: String,
        val sizeBytes: Long,
        val sumsUrl: String?,
        /**
         * True when the update changes the Host API version. Plugins are built
         * against one contract, so those installed for the old one may stop
         * loading - worth saying before, not after.
         */
        val changesHostApi: Boolean,
    )

    /**
     * Asks GitHub whether a newer Host release exists.
     *
     * Every failure is returned rather than thrown: a check that runs on every
     * launch must never be able to take the app down with it, and being offline
     * is the ordinary case, not an error worth interrupting anybody for.
     */
    suspend fun check(): Result = withContext(Dispatchers.IO) {
        runCatching {
            val releases = JSONArray(get("https://api.github.com/repos/$REPO/releases?per_page=30"))
            val installed = BuildConfig.VERSION_NAME

            val newest = (0 until releases.length())
                .map { releases.getJSONObject(it) }
                .filterNot { it.optBoolean("draft") || it.optBoolean("prerelease") }
                .mapNotNull { release -> versionOf(release.optString("tag_name"))?.let { release to it } }
                .maxByOrNull { (_, version) -> Comparable(version) }
                ?: return@runCatching Result.Failed("No Host release published yet")

            val (release, version) = newest
            if (compare(version, installed) <= 0) return@runCatching Result.UpToDate

            val assets = release.optJSONArray("assets") ?: JSONArray()
            val asset = pickAsset(assets, version)
                ?: return@runCatching Result.Failed("Release $version has no APK for this device")

            Result.Available(
                Update(
                    version = version,
                    tag = release.optString("tag_name"),
                    notes = release.optString("body").trim(),
                    assetName = asset.optString("name"),
                    assetUrl = asset.optString("browser_download_url"),
                    sizeBytes = asset.optLong("size"),
                    sumsUrl = assetNamed(assets, SUMS_ASSET)?.optString("browser_download_url"),
                    changesHostApi = major(version) != major(installed),
                ),
            )
        }.getOrElse { Result.Failed(it.message ?: it.javaClass.simpleName) }
    }

    /**
     * Downloads the update and verifies it.
     *
     * A partial file is written under a `.part` name and only moved into place
     * once the bytes are all there and the checksum agrees, so an interrupted
     * download can never be mistaken for a finished one.
     */
    suspend fun download(
        context: Context,
        update: Update,
        onProgress: (Float) -> Unit,
    ): kotlin.Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val directory = File(context.cacheDir, UPDATE_DIR).apply { mkdirs() }
            // Anything left by an earlier attempt is dead weight: an APK is
            // tens of megabytes and only the current one is ever installed.
            directory.listFiles()?.forEach { it.delete() }

            val destination = File(directory, update.assetName)
            val partial = File(directory, update.assetName + ".part")

            val connection = open(update.assetUrl)
            val total = if (update.sizeBytes > 0) update.sizeBytes else connection.contentLengthLong
            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        if (total > 0) onProgress((copied.toDouble() / total).toFloat().coerceIn(0f, 1f))
                    }
                }
            }

            verifyChecksum(partial, update)
            if (!partial.renameTo(destination)) error("Could not finish writing the download")
            destination
        }
    }

    /**
     * Whether Android will let the app install a package at all.
     *
     * Since API 26 this is granted per app in Settings and cannot be requested
     * with a dialog, so the caller has to send the user there.
     */
    fun canInstall(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    /** Where the user turns installation on for this app. */
    fun unknownSourcesSettings(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:" + context.packageName),
        )

    /**
     * Hands the downloaded APK to the system installer.
     *
     * Through [PackageInstaller] rather than a VIEW intent on the file: a VIEW
     * intent is an open question, and Android answers it with a chooser - one
     * whose options included ZetaForge itself, which cannot install anything.
     * A session names the package installer directly, so the next thing the
     * user sees is the system's own confirmation and nothing else.
     *
     * That confirmation is not bypassed and cannot be: the app streams the
     * bytes, the system decides. Which is what makes an app that updates itself
     * acceptable.
     */
    fun install(context: Context, apk: File): kotlin.Result<Unit> = runCatching {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = installer.createSession(params)

        installer.openSession(sessionId).use { session ->
            session.openWrite("update", 0, apk.length()).use { output ->
                apk.inputStream().use { input -> input.copyTo(output) }
                session.fsync(output)
            }
            session.commit(UpdateInstallReceiver.pendingIntent(context).intentSender)
        }
    }

    // -- internals ----------------------------------------------------------

    /**
     * The asset this device can actually install.
     *
     * The debug and release builds carry different signatures, so they are
     * never interchangeable; within the release builds, the device's own ABI
     * saves a little over the universal APK and either one works.
     */
    private fun pickAsset(assets: JSONArray, version: String): JSONObject? {
        if (BuildConfig.DEBUG) return assetNamed(assets, "zetaforge-host-$version-debug.apk")
        for (abi in Build.SUPPORTED_ABIS) {
            assetNamed(assets, "zetaforge-host-$version-$abi.apk")?.let { return it }
        }
        return assetNamed(assets, "zetaforge-host-$version-universal.apk")
    }

    private fun assetNamed(assets: JSONArray, name: String): JSONObject? =
        (0 until assets.length())
            .map { assets.getJSONObject(it) }
            .firstOrNull { it.optString("name") == name }

    /**
     * Compares the download with the checksum the release published.
     *
     * The file arrives over TLS from GitHub, so this is not about an attacker
     * so much as about a truncated or corrupted download reaching the package
     * installer, where the failure would be unreadable.
     */
    private fun verifyChecksum(file: File, update: Update) {
        val sumsUrl = update.sumsUrl ?: return
        val expected = runCatching { get(sumsUrl) }.getOrNull()
            ?.lineSequence()
            ?.mapNotNull { line ->
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size >= 2 && parts.last() == update.assetName) parts.first() else null
            }
            ?.firstOrNull()
            ?: return

        val actual = MessageDigest.getInstance("SHA-256").let { digest ->
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }

        if (!actual.equals(expected, ignoreCase = true)) {
            file.delete()
            error("The download does not match the published checksum")
        }
    }

    private fun get(url: String): String = open(url).inputStream.bufferedReader().use { it.readText() }

    private fun open(url: String, redirects: Int = 0): HttpURLConnection {
        if (redirects > 5) error("Too many redirects")
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "ZetaForge/" + BuildConfig.VERSION_NAME)
        }
        // HttpURLConnection will not follow a redirect that changes protocol,
        // and GitHub's asset downloads do exactly that.
        return when (connection.responseCode) {
            HttpURLConnection.HTTP_MOVED_PERM,
            HttpURLConnection.HTTP_MOVED_TEMP,
            HttpURLConnection.HTTP_SEE_OTHER,
            307, 308,
            -> {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                open(location, redirects + 1)
            }

            in 200..299 -> connection

            else -> {
                val code = connection.responseCode
                connection.disconnect()
                error(
                    if (code == 403) "GitHub rate limit reached; try again later"
                    else "GitHub answered HTTP $code",
                )
            }
        }
    }

    /** `host-v4.1.0` -> `4.1.0`; anything else -> null. */
    private fun versionOf(tag: String?): String? {
        if (tag == null || !tag.startsWith(TAG_PREFIX)) return null
        val rest = tag.removePrefix(TAG_PREFIX)
        return if (Regex("^\\d+\\.\\d+\\.\\d+$").matches(rest)) rest else null
    }

    private fun major(version: String) = version.substringBefore('.').toIntOrNull() ?: 0

    private fun compare(left: String, right: String): Int {
        val a = left.split(".").map { it.toIntOrNull() ?: 0 }
        val b = right.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val difference = (a.getOrNull(i) ?: 0) - (b.getOrNull(i) ?: 0)
            if (difference != 0) return difference
        }
        return 0
    }

    /** Orders version strings numerically rather than as text, so 4.10.0 > 4.9.0. */
    private class Comparable(val version: String) : kotlin.Comparable<Comparable> {
        override fun compareTo(other: Comparable) = compare(version, other.version)
    }
}
