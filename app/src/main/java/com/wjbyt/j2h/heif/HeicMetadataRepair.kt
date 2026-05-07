package com.wjbyt.j2h.heif

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import java.io.ByteArrayOutputStream

/**
 * In-place HEIC metadata repair: flips infe flag bit 0 ("item is hidden") on
 * Exif and tile items so that vivo / Samsung / consumer galleries treat the
 * file the way they expect. Same fix as our HEIC writers now apply at write
 * time; this is for files already converted with older builds.
 *
 * Works as a byte-level patch (no re-encoding, no container resize): the
 * affected flag bytes are at known offsets within each infe box and we just
 * OR-in 0x01 to the third flag byte.
 */
object HeicMetadataRepair {

    sealed interface Result {
        data class Ok(val patchedItems: Int) : Result
        data class Skipped(val reason: String) : Result
        data class Failed(val reason: String) : Result
    }

    fun repair(context: Context, file: DocumentFile): Result {
        if (!(file.name?.lowercase()?.endsWith(".heic") ?: false)) {
            return Result.Skipped("非 HEIC")
        }
        val bytes = try {
            context.contentResolver.openInputStream(file.uri)?.use { it.readBytes() }
        } catch (e: Exception) { return Result.Failed("读取失败: ${e.message}") }
            ?: return Result.Failed("无法打开")

        // Phase 1: byte-flag patch (legacy, idempotent).
        val (afterFlags, flagCount) = try { patchInfeFlags(bytes) }
            catch (e: Exception) { return Result.Failed("解析失败: ${e.message}") }

        // Phase 2: add JPEG-style "Exif\0\0" marker to the existing Exif item
        // if it's missing. This is what makes ExifInterface (and vivo gallery)
        // actually read EXIF tags from old HEICs we converted before the fix.
        val markerResult = try {
            HeifExifInjector.repairExifMarker(afterFlags)
        } catch (e: Exception) {
            return Result.Failed("Exif marker 修复异常: ${e.message?.take(80)}")
        }
        val (final, markerPatched) = when (markerResult) {
            is HeifExifInjector.RepairResult.Patched -> markerResult.bytes to true
            HeifExifInjector.RepairResult.AlreadyOk  -> afterFlags to false
            is HeifExifInjector.RepairResult.CannotRepair -> {
                // Couldn't byte-patch — keep flag fixes only.
                afterFlags to false
            }
        }

        if (flagCount == 0 && !markerPatched) return Result.Skipped("无需修复")

        try {
            context.contentResolver.openOutputStream(file.uri, "wt")?.use { it.write(final) }
                ?: return Result.Failed("无法写回")
        } catch (e: Exception) { return Result.Failed("写回失败: ${e.message}") }

        return Result.Ok(flagCount + (if (markerPatched) 1 else 0))
    }

    /**
     * Returns (newBytes, patchedItemCount). Patches:
     * - hvc1 tiles (when a 'grid' item exists) → hidden (bit 0 set)
     * - Exif item → NOT hidden (bit 0 cleared). HEIF spec reserves the
     *   hidden flag for image items; metadata items shouldn't carry it,
     *   and some galleries skip lookup of items that do.
     */
    private fun patchInfeFlags(data: ByteArray): Pair<ByteArray, Int> {
        val top = parseTopLevel(data)
        val meta = top.firstOrNull { it.type == "meta" }
            ?: return data to 0
        val subStart = meta.offset + meta.headerSize + 4
        val subEnd = meta.offset + meta.size
        val subs = parseList(data, subStart, subEnd)
        val iinf = subs.firstOrNull { it.type == "iinf" } ?: return data to 0

        // First pass: figure out item layout — does this file have a grid?
        var hasGrid = false
        forEachInfe(data, iinf) { _, type, _ ->
            if (type == "grid") hasGrid = true
        }

        // Second pass: write modifications into a copy of the byte array.
        val out = data.copyOf()
        var patched = 0
        forEachInfe(data, iinf) { infeOffset, type, currentFlags ->
            // flag bytes at infeOffset+9, +10, +11 (after 4-byte size + 4-byte
            // type + 1-byte version). Bit 0 of the third flag byte is "hidden".
            val byteIndex = infeOffset + 11
            val isHidden = (currentFlags and 1) != 0
            when {
                type == "Exif" && isHidden -> {
                    // Clear hidden bit so galleries pick the metadata up.
                    out[byteIndex] = (out[byteIndex].toInt() and 0xFE).toByte()
                    patched++
                }
                hasGrid && type == "hvc1" && !isHidden -> {
                    // Tiles must be hidden so they aren't displayed individually.
                    out[byteIndex] = (out[byteIndex].toInt() or 0x01).toByte()
                    patched++
                }
            }
        }
        return out to patched
    }

    /** Iterate infe entries inside an iinf box; callback receives (infe.offset, item_type, current_flags). */
    private inline fun forEachInfe(
        data: ByteArray, iinf: Box, body: (Int, String, Int) -> Unit
    ) {
        var p = iinf.offset.toInt() + iinf.headerSize
        val ver = data[p].toInt() and 0xFF
        p += 4 // skip iinf version+flags
        val cnt = if (ver == 0) {
            readU16(data, p.toLong()).also { p += 2 }
        } else {
            readU32(data, p.toLong()).toInt().also { p += 4 }
        }
        for (i in 0 until cnt) {
            val sz = readU32(data, p.toLong()).toInt()
            val infeOffset = p
            // infe box layout (within payload):
            //   [0]   version
            //   [1..3] flags (24-bit)
            //   for v2/v3: itemId (2/4), prot_idx (2), item_type (4cc), name...
            val infeVer = data[p + 8].toInt() and 0xFF
            val flags = ((data[p + 9].toInt() and 0xFF) shl 16) or
                        ((data[p + 10].toInt() and 0xFF) shl 8) or
                         (data[p + 11].toInt() and 0xFF)
            val itemType: String = if (infeVer >= 2) {
                val typeOffset = p + 12 + (if (infeVer == 2) 2 else 4) + 2
                String(data, typeOffset, 4, Charsets.US_ASCII)
            } else ""
            body(infeOffset, itemType, flags)
            p += sz
        }
    }

    private data class Box(val type: String, val offset: Long, val size: Long, val headerSize: Int)

    private fun parseTopLevel(data: ByteArray) = parseList(data, 0L, data.size.toLong())

    private fun parseList(data: ByteArray, start: Long, end: Long): List<Box> {
        val out = mutableListOf<Box>()
        var p = start
        while (p < end - 8) {
            val sz32 = readU32(data, p)
            val type = String(data, (p + 4).toInt(), 4, Charsets.US_ASCII)
            val (size, hdr) = when (sz32) {
                1L -> readU64(data, p + 8) to 16
                0L -> (end - p) to 8
                else -> sz32 to 8
            }
            if (size < hdr || p + size > end) break
            out += Box(type, p, size, hdr)
            p += size
        }
        return out
    }

    private fun readU16(d: ByteArray, p: Long): Int {
        val i = p.toInt()
        return ((d[i].toInt() and 0xFF) shl 8) or (d[i + 1].toInt() and 0xFF)
    }

    private fun readU32(d: ByteArray, p: Long): Long {
        val i = p.toInt()
        return ((d[i].toLong() and 0xFF) shl 24) or
                ((d[i + 1].toLong() and 0xFF) shl 16) or
                ((d[i + 2].toLong() and 0xFF) shl 8) or
                (d[i + 3].toLong() and 0xFF)
    }

    private fun readU64(d: ByteArray, p: Long): Long {
        val i = p.toInt()
        var v = 0L
        for (k in 0 until 8) v = (v shl 8) or (d[i + k].toLong() and 0xFF)
        return v
    }
}
