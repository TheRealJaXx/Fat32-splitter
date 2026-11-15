package com.example.format

import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class PartitionScheme(val label: String) {
  MBR("MBR"),
  GPT("GPT"),
  RAW_OR_EMPTY("Unpartitioned / Raw"),
  UNKNOWN("Unknown"),
}

enum class TargetFilesystem(
  val id: String,
  val title: String,
  val mbrTypeByte: Int,
  val badge: String,
  val description: String,
  val defaultClusterBytes: Int,
  val clusterOptionsBytes: List<Int>,
) {
  MBR_NTFS(
    id = "mbr_ntfs",
    title = "MBR + NTFS",
    mbrTypeByte = 0x07,
    badge = ">4 GiB ISOs (IrisMAN / prepISO / webMAN)",
    description = "Stores full-size PS3 ISOs and MKVs without splitting. Supported via prepISO & IrisMAN.",
    defaultClusterBytes = 4096,
    clusterOptionsBytes = listOf(4096, 8192, 16384, 32768, 65536),
  ),
  MBR_FAT32(
    id = "mbr_fat32",
    title = "MBR + FAT32",
    mbrTypeByte = 0x0C,
    badge = "Universal PS3 XMB & multiMAN",
    description = "Native PS3 XMB & multiMAN compatibility. Pair with Split tab for files over 4 GiB.",
    defaultClusterBytes = 32768,
    clusterOptionsBytes = listOf(4096, 8192, 16384, 32768),
  ),
}

data class UsbDriveInfo(
  val deviceId: Int,
  val deviceName: String,
  val vendorName: String,
  val productName: String,
  val vendorId: Int,
  val productId: Int,
  val hasPermission: Boolean,
  val probed: Boolean = false,
  val sectorSizeBytes: Int = 512,
  val totalSectors: Long = 0L,
  val detectedScheme: PartitionScheme = PartitionScheme.UNKNOWN,
  val probeError: String? = null,
) {
  val capacityBytes: Long
    get() = totalSectors * sectorSizeBytes.toLong()

  val displayTitle: String
    get() = listOf(vendorName, productName).filter { it.isNotBlank() }.joinToString(" ").ifBlank {
      "USB Mass Storage"
    }

  val vidPidHex: String
    get() = String.format("%04X:%04X", vendorId and 0xFFFF, productId and 0xFFFF)
}

sealed interface FormatJobState {
  data object Idle : FormatJobState

  data class Probing(val deviceTitle: String) : FormatJobState

  data class Formatting(
    val deviceTitle: String,
    val filesystem: TargetFilesystem,
    val stageLabel: String,
    val progress: Float,
  ) : FormatJobState

  data class Completed(
    val deviceTitle: String,
    val filesystem: TargetFilesystem,
    val volumeLabel: String,
    val partitionStartLba: Long,
    val partitionSectors: Long,
    val sectorSizeBytes: Int,
    val clusterSizeBytes: Int,
    val elapsedMillis: Long,
  ) : FormatJobState

  data class Failed(val message: String) : FormatJobState
}

interface BlockDevice : AutoCloseable {
  val sectorSizeBytes: Int
  val totalSectors: Long

  suspend fun readSectors(startLba: Long, sectorCount: Int): ByteArray

  suspend fun writeSectors(startLba: Long, data: ByteArray)

  suspend fun syncCache()
}

internal object LittleEndian {
  fun putU16(buf: ByteArray, offset: Int, value: Int) {
    buf[offset] = (value and 0xFF).toByte()
    buf[offset + 1] = ((value ushr 8) and 0xFF).toByte()
  }

  fun putU32(buf: ByteArray, offset: Int, value: Long) {
    buf[offset] = (value and 0xFF).toByte()
    buf[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    buf[offset + 2] = ((value ushr 16) and 0xFF).toByte()
    buf[offset + 3] = ((value ushr 24) and 0xFF).toByte()
  }

  fun putU64(buf: ByteArray, offset: Int, value: Long) {
    for (i in 0 until 8) {
      buf[offset + i] = ((value ushr (i * 8)) and 0xFF).toByte()
    }
  }

  fun getU16(buf: ByteArray, offset: Int): Int =
    (buf[offset].toInt() and 0xFF) or ((buf[offset + 1].toInt() and 0xFF) shl 8)

  fun getU32(buf: ByteArray, offset: Int): Long =
    (buf[offset].toLong() and 0xFFL) or
      ((buf[offset + 1].toLong() and 0xFFL) shl 8) or
      ((buf[offset + 2].toLong() and 0xFFL) shl 16) or
      ((buf[offset + 3].toLong() and 0xFFL) shl 24)

  fun getU64(buf: ByteArray, offset: Int): Long {
    var v = 0L
    for (i in 0 until 8) {
      v = v or ((buf[offset + i].toLong() and 0xFFL) shl (i * 8))
    }
    return v
  }

  fun asciiBytes(text: String, fixedLen: Int, padByte: Byte = 0x20): ByteArray {
    val out = ByteArray(fixedLen) { padByte }
    val raw = text.toByteArray(Charsets.US_ASCII)
    raw.copyInto(out, 0, 0, minOf(raw.size, fixedLen))
    return out
  }

  fun utf16LeBytes(text: String): ByteArray = text.toByteArray(Charsets.UTF_16LE)

  fun wrapLe(buf: ByteArray): ByteBuffer = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
}
