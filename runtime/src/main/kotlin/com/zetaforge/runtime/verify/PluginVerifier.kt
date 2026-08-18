package com.zetaforge.runtime.verify

import com.zetaforge.runtime.pkg.ZetaPackage
import com.zetaforge.sdk.ZetaSdk

/** Outcome of a single verification step. */
data class VerificationCheck(
    val name: String,
    val passed: Boolean,
    val detail: String,
    /** Warnings do not prevent installation, they are surfaced to the user. */
    val warning: Boolean = false,
)

/** Aggregated verification outcome for one package. */
data class VerificationResult(
    val checks: List<VerificationCheck>,
) {
    val failures: List<VerificationCheck> get() = checks.filter { !it.passed && !it.warning }
    val warnings: List<VerificationCheck> get() = checks.filter { it.warning }
    val isValid: Boolean get() = failures.isEmpty()

    fun failureMessage(): String = failures.joinToString("; ") { "${it.name}: ${it.detail}" }
}

/**
 * Extension point for package validation.
 *
 * The PoC ships [BasicPluginVerifier] (structure + manifest + checksum + API
 * range). A future `SignaturePluginVerifier` implements the same interface and
 * is chained in front of it without touching the runtime.
 */
interface PluginVerifier {
    fun verify(plugin: ZetaPackage): VerificationResult
}

/** Runs several verifiers and merges their results. */
class CompositePluginVerifier(private val verifiers: List<PluginVerifier>) : PluginVerifier {
    override fun verify(plugin: ZetaPackage): VerificationResult =
        VerificationResult(verifiers.flatMap { it.verify(plugin).checks })
}

/**
 * Structural verifier used by the PoC.
 *
 * @param hostApiVersion API version implemented by this Host build.
 * @param hostPermissions permissions actually declared by the Host manifest;
 *   plugin requests outside this set are reported as warnings because a plugin
 *   runs with the Host's permissions and cannot obtain more on its own.
 * @param expectedSha256 optional pinned checksum (used when re-verifying an
 *   already installed package against the value recorded at install time).
 */
class BasicPluginVerifier(
    private val hostApiVersion: Int = ZetaSdk.HOST_API_VERSION,
    private val hostPermissions: Set<String> = emptySet(),
    private val hostSdkInt: Int = Int.MAX_VALUE,
    private val expectedSha256: String? = null,
) : PluginVerifier {

    override fun verify(plugin: ZetaPackage): VerificationResult {
        val manifest = plugin.manifest
        val checks = mutableListOf<VerificationCheck>()

        checks += VerificationCheck(
            name = "structure",
            passed = plugin.dexEntries.isNotEmpty(),
            detail = "${plugin.dexEntries.size} DEX file(s): ${plugin.dexEntries.joinToString()}",
        )

        checks += VerificationCheck(
            name = "manifest",
            passed = manifest.formatVersion <= ZetaSdk.MANIFEST_FORMAT_VERSION,
            detail = "formatVersion=${manifest.formatVersion}, pluginId=${manifest.pluginId}, " +
                "version=${manifest.version}",
        )

        checks += VerificationCheck(
            name = "entryPoint",
            passed = manifest.entryPoint.isNotBlank() && manifest.entryPoint.contains('.'),
            detail = manifest.entryPoint,
        )

        checks += VerificationCheck(
            name = "hostApi",
            passed = manifest.isCompatibleWith(hostApiVersion),
            detail = if (manifest.isCompatibleWith(hostApiVersion)) {
                "host API $hostApiVersion within [${manifest.minHostApi}..${manifest.maxHostApi}]"
            } else {
                "Plugin incompatible with this Host: requires API " +
                    "[${manifest.minHostApi}..${manifest.maxHostApi}], Host implements $hostApiVersion"
            },
        )

        checks += VerificationCheck(
            name = "minSdk",
            passed = manifest.minSdk <= hostSdkInt,
            detail = "plugin minSdk=${manifest.minSdk}, device API=$hostSdkInt",
        )

        val checksumOk = expectedSha256 == null || expectedSha256.equals(plugin.sha256, ignoreCase = true)
        checks += VerificationCheck(
            name = "checksum",
            passed = checksumOk,
            detail = if (checksumOk) "sha256=${plugin.sha256}" else
                "expected ${expectedSha256?.take(16)}..., got ${plugin.sha256.take(16)}...",
        )

        val missingPermissions = manifest.permissions.filterNot { hostPermissions.contains(it) }
        if (missingPermissions.isNotEmpty()) {
            checks += VerificationCheck(
                name = "permissions",
                passed = false,
                warning = true,
                detail = "Host does not declare: ${missingPermissions.joinToString()}. " +
                    "The plugin runs with the Host's permissions only.",
            )
        } else if (manifest.permissions.isNotEmpty()) {
            checks += VerificationCheck(
                name = "permissions",
                passed = true,
                detail = "granted by Host: ${manifest.permissions.joinToString()}",
            )
        }

        if (manifest.signature == null) {
            checks += VerificationCheck(
                name = "signature",
                passed = true,
                warning = true,
                detail = "Package is unsigned. Trust comes from the file source only.",
            )
        }

        return VerificationResult(checks)
    }
}
