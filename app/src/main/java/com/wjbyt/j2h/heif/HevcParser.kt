package com.wjbyt.j2h.heif

import java.io.ByteArrayOutputStream

/**
 * HEVC NAL-unit-level parsing and HEVCDecoderConfigurationRecord (hvcC) construction.
 *
 * The bitstream emitted by Android's MediaCodec uses Annex-B framing: NAL units
 * separated by 0x000001 / 0x00000001 start codes. HEIF storage requires length-
 * prefixed NAL units instead, plus an hvcC config record built from the VPS/SPS/PPS
 * parameter sets — see ISO/IEC 14496-15 §8.3.
 */
object HevcParser {

    // HEVC NAL unit types (NAL header byte 0, bits 1..6).
    const val NAL_VPS = 32
    const val NAL_SPS = 33
    const val NAL_PPS = 34

    data class NalUnit(val type: Int, val data: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is NalUnit && type == other.type && data.contentEquals(other.data)
        override fun hashCode(): Int = 31 * type + data.contentHashCode()
    }

    /** Split an Annex-B byte stream (with 0x000001 / 0x00000001 start codes) into NAL units. */
    fun splitNalUnits(annexB: ByteArray): List<NalUnit> {
        val out = mutableListOf<NalUnit>()
        val starts = findStartCodes(annexB)
        for (i in starts.indices) {
            val (scStart, scLen) = starts[i]
            val payloadStart = scStart + scLen
            val payloadEnd = if (i + 1 < starts.size) starts[i + 1].first else annexB.size
            if (payloadEnd <= payloadStart) continue
            val nal = annexB.copyOfRange(payloadStart, payloadEnd)
            // NAL header byte 0: forbidden(1) + nal_unit_type(6) + layer_id_high(1)
            val type = (nal[0].toInt() ushr 1) and 0x3F
            out += NalUnit(type, nal)
        }
        return out
    }

    /** Returns positions of 0x000001 / 0x00000001 start codes as (offset, length). */
    private fun findStartCodes(d: ByteArray): List<Pair<Int, Int>> {
        val out = mutableListOf<Pair<Int, Int>>()
        var i = 0
        while (i + 2 < d.size) {
            if (d[i].toInt() == 0 && d[i + 1].toInt() == 0) {
                if (d[i + 2].toInt() == 1) {
                    out += i to 3
                    i += 3
                    continue
                }
                if (i + 3 < d.size && d[i + 2].toInt() == 0 && d[i + 3].toInt() == 1) {
                    out += i to 4
                    i += 4
                    continue
                }
            }
            i++
        }
        return out
    }

    /** Re-emit NAL units in length-prefixed form (4-byte BE length per unit) for HEIF mdat. */
    fun toLengthPrefixed(nals: List<NalUnit>): ByteArray {
        val out = ByteArrayOutputStream()
        for (n in nals) {
            val len = n.data.size
            out.write((len ushr 24) and 0xFF)
            out.write((len ushr 16) and 0xFF)
            out.write((len ushr 8) and 0xFF)
            out.write(len and 0xFF)
            out.write(n.data)
        }
        return out.toByteArray()
    }

    // ---------- hvcC builder ----------

    /**
     * Build an HEVCDecoderConfigurationRecord (the body of the 'hvcC' box) from
     * the VPS/SPS/PPS parameter sets. Most of the profile/tier/level / chroma /
     * bit-depth metadata fields are extracted from the SPS (specifically its
     * profile_tier_level() and seq_parameter_set_rbsp() sections).
     */
    fun buildHvcC(vps: ByteArray, sps: ByteArray, pps: ByteArray): ByteArray {
        val s = SpsInfo.parse(sps)
        val out = ByteArrayOutputStream()

        // configurationVersion
        out.write(1)
        // general_profile_space (2) | general_tier_flag (1) | general_profile_idc (5)
        out.write(((s.profileSpace and 0x3) shl 6) or
                  ((if (s.tierFlag) 1 else 0) shl 5) or
                  (s.profileIdc and 0x1F))
        // general_profile_compatibility_flags (32)
        writeU32BE(out, s.profileCompatFlags)
        // general_constraint_indicator_flags (48 bits = 6 bytes)
        for (b in s.constraintIndicators) out.write(b.toInt() and 0xFF)
        // general_level_idc (8)
        out.write(s.levelIdc)
        // 4 reserved '1111' + min_spatial_segmentation_idc (12)
        writeU16BE(out, 0xF000)
        // 6 reserved '111111' + parallelismType (2)
        out.write(0xFC)
        // 6 reserved '111111' + chromaFormat (2)
        out.write(0xFC or (s.chromaFormatIdc and 0x3))
        // 5 reserved '11111' + bitDepthLumaMinus8 (3)
        out.write(0xF8 or (s.bitDepthLumaMinus8 and 0x7))
        // 5 reserved '11111' + bitDepthChromaMinus8 (3)
        out.write(0xF8 or (s.bitDepthChromaMinus8 and 0x7))
        // avgFrameRate (16) — 0 for stills
        writeU16BE(out, 0)
        // constantFrameRate(2) | numTemporalLayers(3) | temporalIdNested(1) | lengthSizeMinusOne(2)
        // 0 | 1 | 1 | 3 (= 4-byte length prefix)
        out.write((0 shl 6) or (1 shl 3) or (1 shl 2) or 3)

        // numOfArrays
        out.write(3)

        // VPS array
        writeNalArray(out, NAL_VPS, listOf(vps))
        // SPS array
        writeNalArray(out, NAL_SPS, listOf(sps))
        // PPS array
        writeNalArray(out, NAL_PPS, listOf(pps))

        return out.toByteArray()
    }

    private fun writeNalArray(out: ByteArrayOutputStream, nalType: Int, nals: List<ByteArray>) {
        // array_completeness=1 | reserved=0 | NAL_unit_type (6 bits)
        out.write((1 shl 7) or (nalType and 0x3F))
        // numNalus (16)
        writeU16BE(out, nals.size)
        for (n in nals) {
            writeU16BE(out, n.size)
            out.write(n)
        }
    }

    private fun writeU16BE(o: ByteArrayOutputStream, v: Int) {
        o.write((v ushr 8) and 0xFF)
        o.write(v and 0xFF)
    }

    private fun writeU32BE(o: ByteArrayOutputStream, v: Long) {
        o.write(((v ushr 24) and 0xFF).toInt())
        o.write(((v ushr 16) and 0xFF).toInt())
        o.write(((v ushr 8) and 0xFF).toInt())
        o.write((v and 0xFF).toInt())
    }

    // ---------- SPS parsing (just enough fields for hvcC) ----------

    private data class SpsInfo(
        val profileSpace: Int,
        val tierFlag: Boolean,
        val profileIdc: Int,
        val profileCompatFlags: Long,
        val constraintIndicators: ByteArray,
        val levelIdc: Int,
        val chromaFormatIdc: Int,
        val bitDepthLumaMinus8: Int,
        val bitDepthChromaMinus8: Int,
        val width: Int,
        val height: Int
    ) {
        companion object {
            fun parse(spsNal: ByteArray): SpsInfo {
                // Strip emulation prevention bytes (0x000003 -> 0x0000) from NAL RBSP.
                val rbsp = removeEmulationPrevention(spsNal, 2 /* skip NAL header */)
                val br = BitReader(rbsp)
                br.read(4)              // sps_video_parameter_set_id
                val maxSubLayersMinus1 = br.read(3)
                br.read(1)              // sps_temporal_id_nesting_flag

                // profile_tier_level(profilePresentFlag=1, maxSubLayersMinus1)
                val profileSpace = br.read(2)
                val tierFlag = br.read(1) == 1
                val profileIdc = br.read(5)
                var profileCompatFlags = 0L
                for (j in 0 until 32) profileCompatFlags = (profileCompatFlags shl 1) or br.read(1).toLong()
                val ci = ByteArray(6)
                for (j in 0 until 48) {
                    val byte = j / 8
                    val bit = 7 - (j % 8)
                    ci[byte] = (ci[byte].toInt() or (br.read(1) shl bit)).toByte()
                }
                val levelIdc = br.read(8)

                // sub_layer flags
                val subLayerProfilePresent = BooleanArray(maxSubLayersMinus1)
                val subLayerLevelPresent = BooleanArray(maxSubLayersMinus1)
                for (i in 0 until maxSubLayersMinus1) {
                    subLayerProfilePresent[i] = br.read(1) == 1
                    subLayerLevelPresent[i] = br.read(1) == 1
                }
                if (maxSubLayersMinus1 > 0) {
                    for (i in maxSubLayersMinus1 until 8) br.read(2) // reserved_zero_2bits
                }
                for (i in 0 until maxSubLayersMinus1) {
                    if (subLayerProfilePresent[i]) {
                        br.read(2 + 1 + 5)
                        br.read(32)
                        br.read(48)
                    }
                    if (subLayerLevelPresent[i]) br.read(8)
                }

                br.readUE()                       // sps_seq_parameter_set_id
                val chromaFormatIdc = br.readUE()
                if (chromaFormatIdc == 3) br.read(1) // separate_colour_plane_flag
                val width = br.readUE()
                val height = br.readUE()
                val conformanceWindowFlag = br.read(1)
                if (conformanceWindowFlag == 1) {
                    br.readUE(); br.readUE(); br.readUE(); br.readUE()
                }
                val bitDepthLumaMinus8 = br.readUE()
                val bitDepthChromaMinus8 = br.readUE()

                return SpsInfo(
                    profileSpace, tierFlag, profileIdc, profileCompatFlags,
                    ci, levelIdc, chromaFormatIdc, bitDepthLumaMinus8, bitDepthChromaMinus8,
                    width, height
                )
            }
        }
    }

    private fun removeEmulationPrevention(nal: ByteArray, startOffset: Int): ByteArray {
        val out = ByteArrayOutputStream(nal.size - startOffset)
        var i = startOffset
        while (i < nal.size) {
            if (i + 2 < nal.size && nal[i].toInt() == 0 && nal[i + 1].toInt() == 0 && nal[i + 2].toInt() == 3) {
                out.write(0); out.write(0)
                i += 3
            } else {
                out.write(nal[i].toInt() and 0xFF)
                i++
            }
        }
        return out.toByteArray()
    }

    private class BitReader(val data: ByteArray) {
        private var bytePos = 0
        private var bitPos = 0
        fun read(n: Int): Int {
            var v = 0
            for (i in 0 until n) {
                v = (v shl 1) or readBit()
            }
            return v
        }
        private fun readBit(): Int {
            if (bytePos >= data.size) return 0
            val b = (data[bytePos].toInt() ushr (7 - bitPos)) and 1
            bitPos++
            if (bitPos == 8) { bitPos = 0; bytePos++ }
            return b
        }
        /** Unsigned Exp-Golomb. */
        fun readUE(): Int {
            var zeros = 0
            while (readBit() == 0 && zeros < 32 && bytePos < data.size) zeros++
            return ((1 shl zeros) - 1) + read(zeros)
        }
    }
}
