package com.zetaforge.runtime.pkg

import com.zetaforge.runtime.manifest.ZetaManifest
import com.zetaforge.runtime.manifest.ZetaManifestException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipException
import java.util.zip.ZipFile

/** Thrown when a `.zeta` archive is structurally unusable. */
class ZetaPackageException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * A `.zeta` archive that has been opened and structurally validated.
 *
 * Holding a [ZetaPackage] means: the file is a readable ZIP, it carries a
 * parseable manifest, and every DEX the manifest declares physically exists in
 * the archive with the announced content hash.
 */
data class ZetaPackage(
    val file: File,
    val manifest: ZetaManifest,
    /** SHA-256 of the whole archive, computed at import time. */
    val sha256: String,
    val sizeBytes: Long,
    /** Entry names of the DEX files, in load order. */
    val dexEntries: List<String>,
)

/** Reads and structurally validates `.zeta` archives. */
object ZetaPackageReader {

    const val MANIFEST_ENTRY = "manifest.json"

    private val DEX_MAGIC = byteArrayOf(0x64, 0x65, 0x78, 0x0A) // "dex\n"

    /**
     * Opens [file], validates it and returns the resulting package description.
     *
     * @throws ZetaPackageException when the archive is not a usable plugin package.
     */
    fun read(file: File): ZetaPackage {
        if (!file.isFile) throw ZetaPackageException("Package file does not exist: ${file.absolutePath}")
        if (file.length() == 0L) throw ZetaPackageException("Package file is empty.")

        val zip = try {
            ZipFile(file)
        } catch (e: ZipException) {
            throw ZetaPackageException("Not a valid ZIP archive: ${e.message}", e)
        } catch (e: IOException) {
            throw ZetaPackageException("Cannot open package: ${e.message}", e)
        }

        zip.use {
            val manifestEntry = zip.getEntry(MANIFEST_ENTRY)
                ?: throw ZetaPackageException("Package does not contain $MANIFEST_ENTRY.")

            val manifestJson = zip.getInputStream(manifestEntry).use { input ->
                input.readBytes().toString(Charsets.UTF_8)
            }

            val manifest = try {
                ZetaManifest.parse(manifestJson)
            } catch (e: ZetaManifestException) {
                throw ZetaPackageException(e.message ?: "Invalid manifest.", e)
            }

            manifest.dex.forEach { declared ->
                val entry = zip.getEntry(declared.path)
                    ?: throw ZetaPackageException(
                        "Manifest declares '${declared.path}' but the archive does not contain it."
                    )
                val bytes = zip.getInputStream(entry).use(InputStream::readBytes)
                if (bytes.size < 112 || !bytes.copyOfRange(0, 4).contentEquals(DEX_MAGIC)) {
                    throw ZetaPackageException("'${declared.path}' is not a DEX file (bad magic).")
                }
                if (declared.sha256.isNotBlank()) {
                    val actual = bytes.sha256()
                    if (!actual.equals(declared.sha256, ignoreCase = true)) {
                        throw ZetaPackageException(
                            "Checksum mismatch for '${declared.path}': manifest says " +
                                "${declared.sha256.take(12)}..., archive is ${actual.take(12)}..."
                        )
                    }
                }
            }

            return ZetaPackage(
                file = file,
                manifest = manifest,
                sha256 = file.sha256(),
                sizeBytes = file.length(),
                dexEntries = manifest.dex.map { it.path },
            )
        }
    }
}

/** SHA-256 of a file, streamed so large archives never sit fully in memory. */
fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().buffered().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().toHex()
}

fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256").digest(this).toHex()

private fun ByteArray.toHex(): String {
    val out = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xFF
        out.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
    }
    return out.toString()
}

private const val HEX = "0123456789abcdef"
