package com.zetaforge.builder

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal DEX reader used by the packaging task to *prove* that a produced
 * `classes.dex` really is a DEX file and really contains the classes we claim
 * it contains. It only parses the header and the string table, which is enough
 * to answer "is type `Lfoo/Bar;` referenced by this DEX?".
 */
internal object DexReader {

    private const val HEADER_SIZE = 112
    private val MAGIC = byteArrayOf(0x64, 0x65, 0x78, 0x0A) // "dex\n"

    fun isDex(file: File): Boolean {
        if (file.length() < HEADER_SIZE) return false
        val head = file.inputStream().use { it.readNBytes(8) }
        return head.copyOfRange(0, 4).contentEquals(MAGIC) && head[7] == 0.toByte()
    }

    /** DEX format version, e.g. "035" / "039". */
    fun version(file: File): String {
        val head = file.inputStream().use { it.readNBytes(8) }
        return String(head, 4, 3)
    }

    /** All strings of the DEX string table. Type names appear as `Lfoo/Bar;`. */
    fun readStrings(file: File): List<String> {
        val bytes = file.readBytes()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val stringIdsSize = buf.getInt(56)
        val stringIdsOff = buf.getInt(60)
        val result = ArrayList<String>(stringIdsSize)
        for (i in 0 until stringIdsSize) {
            val dataOff = buf.getInt(stringIdsOff + i * 4)
            result += readMutf8(bytes, dataOff)
        }
        return result
    }

    fun containsType(file: File, fqcn: String): Boolean {
        val descriptor = "L" + fqcn.replace('.', '/') + ";"
        return readStrings(file).contains(descriptor)
    }

    fun containsAnyTypeInPackage(file: File, packagePrefix: String): Boolean {
        val prefix = "L" + packagePrefix.replace('.', '/')
        return readStrings(file).any { it.startsWith(prefix) }
    }

    private fun readMutf8(bytes: ByteArray, offset: Int): String {
        var p = offset
        // utf16_size, ULEB128 encoded, then a NUL terminated MUTF-8 payload.
        var shift = 0
        var utf16Size = 0
        while (true) {
            val b = bytes[p++].toInt()
            utf16Size = utf16Size or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
        }
        val out = StringBuilder(utf16Size)
        while (out.length < utf16Size) {
            val a = bytes[p++].toInt() and 0xFF
            when {
                a and 0x80 == 0 -> out.append(a.toChar())
                a and 0xE0 == 0xC0 -> {
                    val b = bytes[p++].toInt() and 0x3F
                    out.append((((a and 0x1F) shl 6) or b).toChar())
                }
                else -> {
                    val b = bytes[p++].toInt() and 0x3F
                    val c = bytes[p++].toInt() and 0x3F
                    out.append((((a and 0x0F) shl 12) or (b shl 6) or c).toChar())
                }
            }
        }
        return out.toString()
    }
}
