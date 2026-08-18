package com.zetaforge.runtime.manifest

import com.zetaforge.sdk.ZetaSdk
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Thrown when a `manifest.json` cannot be parsed or is semantically invalid. */
class ZetaManifestException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Description of one DEX file declared by a package manifest. */
data class DexEntry(
    val path: String,
    val size: Long,
    val sha256: String,
    val dexVersion: String,
)

/**
 * Parsed, validated `manifest.json` of a `.zeta` package.
 *
 * The format is explicitly versioned ([formatVersion]) and carries forward
 * looking fields (signature, capabilities, dependencies) so packages produced
 * today keep parsing when the runtime grows.
 */
data class ZetaManifest(
    val formatVersion: Int,
    val pluginId: String,
    val name: String,
    val version: String,
    val description: String,
    val author: String,
    val entryPoint: String,
    val minHostApi: Int,
    val maxHostApi: Int,
    val minSdk: Int,
    val permissions: List<String>,
    val capabilities: List<String>,
    val bundledDependencies: List<String>,
    val hostProvidedDependencies: List<String>,
    val dex: List<DexEntry>,
    /** `null` while packages are unsigned; reserved for SignaturePluginVerifier. */
    val signature: PluginSignature?,
) {

    /** True when this plugin can talk to the API version implemented by the Host. */
    fun isCompatibleWith(hostApiVersion: Int = ZetaSdk.HOST_API_VERSION): Boolean =
        hostApiVersion in minHostApi..maxHostApi

    companion object {

        private const val KEY_FORMAT_VERSION = "formatVersion"

        /**
         * Parses and validates a manifest document.
         *
         * @throws ZetaManifestException on malformed JSON or missing/invalid fields.
         */
        fun parse(json: String): ZetaManifest {
            val root = try {
                JSONObject(json)
            } catch (e: JSONException) {
                throw ZetaManifestException("manifest.json is not valid JSON: " + e.message, e)
            }

            val formatVersion = root.optInt(KEY_FORMAT_VERSION, -1)
            if (formatVersion <= 0) {
                throw ZetaManifestException("Missing or invalid '$KEY_FORMAT_VERSION'.")
            }
            if (formatVersion > ZetaSdk.MANIFEST_FORMAT_VERSION) {
                throw ZetaManifestException(
                    "Package format version $formatVersion is newer than the one " +
                        "supported by this Host (${ZetaSdk.MANIFEST_FORMAT_VERSION}). Update ZetaForge."
                )
            }

            val pluginId = root.requireString("pluginId")
            if (!PLUGIN_ID_PATTERN.matches(pluginId)) {
                throw ZetaManifestException("Invalid pluginId '$pluginId': expected reverse-DNS form.")
            }
            val entryPoint = root.requireString("entryPoint")
            if (!CLASS_NAME_PATTERN.matches(entryPoint)) {
                throw ZetaManifestException("Invalid entryPoint '$entryPoint': expected a fully qualified class name.")
            }
            val version = root.requireString("version")
            if (!VERSION_PATTERN.matches(version)) {
                throw ZetaManifestException("Invalid version '$version': expected MAJOR.MINOR.PATCH.")
            }

            val minHostApi = root.optInt("minHostApi", -1)
            val maxHostApi = root.optInt("maxHostApi", -1)
            if (minHostApi <= 0 || maxHostApi <= 0) {
                throw ZetaManifestException("minHostApi/maxHostApi must be positive integers.")
            }
            if (minHostApi > maxHostApi) {
                throw ZetaManifestException("minHostApi ($minHostApi) is greater than maxHostApi ($maxHostApi).")
            }

            val dex = root.optJSONObject("code")
                ?.optJSONArray("dex")
                ?.mapObjects { obj ->
                    DexEntry(
                        path = obj.requireString("path"),
                        size = obj.optLong("size", 0L),
                        sha256 = obj.optString("sha256", ""),
                        dexVersion = obj.optString("dexVersion", ""),
                    )
                }
                .orEmpty()
            if (dex.isEmpty()) {
                throw ZetaManifestException("Manifest declares no DEX file under 'code.dex'.")
            }

            val dependencies = root.optJSONObject("dependencies")

            return ZetaManifest(
                formatVersion = formatVersion,
                pluginId = pluginId,
                name = root.optString("name", pluginId),
                version = version,
                description = root.optString("description", ""),
                author = root.optString("author", ""),
                entryPoint = entryPoint,
                minHostApi = minHostApi,
                maxHostApi = maxHostApi,
                minSdk = root.optInt("minSdk", 1),
                permissions = root.optJSONArray("permissions").toStringList(),
                capabilities = root.optJSONArray("capabilities").toStringList(),
                bundledDependencies = dependencies?.optJSONArray("bundled").toStringList(),
                hostProvidedDependencies = dependencies?.optJSONArray("hostProvided").toStringList(),
                dex = dex,
                signature = PluginSignature.parse(root.optJSONObject("signature")),
            )
        }

        private val PLUGIN_ID_PATTERN = Regex("[a-zA-Z][A-Za-z0-9_]*(\\.[a-zA-Z][A-Za-z0-9_]*)+")
        private val CLASS_NAME_PATTERN = Regex("[a-zA-Z][A-Za-z0-9_]*(\\.[a-zA-Z][A-Za-z0-9_$]*)+")
        private val VERSION_PATTERN = Regex("\\d+\\.\\d+\\.\\d+([-+][A-Za-z0-9.\\-]+)?")
    }
}

/**
 * Placeholder for the future signed-package support. The PoC always produces
 * `"signature": null`; the parser already accepts a populated block so signed
 * packages stay readable by older Hosts.
 */
data class PluginSignature(
    val algorithm: String,
    val certificateSha256: String,
    val value: String,
) {
    companion object {
        fun parse(obj: JSONObject?): PluginSignature? {
            if (obj == null) return null
            return PluginSignature(
                algorithm = obj.optString("algorithm", ""),
                certificateSha256 = obj.optString("certificateSha256", ""),
                value = obj.optString("value", ""),
            )
        }
    }
}

private fun JSONObject.requireString(key: String): String {
    val value = optString(key, "")
    if (value.isBlank()) throw ZetaManifestException("Missing required field '$key'.")
    return value
}

private fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).map { optString(it, "") }.filter { it.isNotBlank() }
}

private fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T): List<T> =
    (0 until length()).mapNotNull { optJSONObject(it) }.map(transform)
