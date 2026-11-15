package com.example.engine

import com.example.model.ChunkSizePreset
import com.example.model.SplitNamingFormat
import java.util.Locale
import kotlin.math.ceil

data class ParsedSplitPart(
  val baseFileName: String,
  val partIndex: Int,
  val format: SplitNamingFormat,
)

object SplitNaming {

  private val ps3Regex = Regex("""^(.+)\.666(\d{2,})$""", RegexOption.IGNORE_CASE)
  private val numericRegex = Regex("""^(.+)\.(\d{3,4})$""")

  fun clampFat32ChunkSize(requestedBytes: Long): Long =
    requestedBytes.coerceIn(1L, ChunkSizePreset.FAT32_MAX_BYTES)

  fun partFileName(
    originalFileName: String,
    zeroBasedPartIndex: Int,
    format: SplitNamingFormat,
  ): String {
    require(zeroBasedPartIndex >= 0) { "Part index must be non-negative" }
    return when (format) {
      SplitNamingFormat.PS3_666 -> {
        val padded = zeroBasedPartIndex.toString().padStart(2, '0')
        "$originalFileName.666$padded"
      }
      SplitNamingFormat.SEQUENTIAL_001 -> {
        val oneBased = zeroBasedPartIndex + 1
        val padded = oneBased.toString().padStart(3, '0')
        "$originalFileName.$padded"
      }
      SplitNamingFormat.ZERO_BASED_000 -> {
        val padded = zeroBasedPartIndex.toString().padStart(3, '0')
        "$originalFileName.$padded"
      }
    }
  }

  fun parseSplitPart(fileName: String): ParsedSplitPart? {
    val ps3Match = ps3Regex.matchEntire(fileName)
    if (ps3Match != null) {
      val base = ps3Match.groupValues[1]
      val index = ps3Match.groupValues[2].toIntOrNull() ?: return null
      return ParsedSplitPart(
        baseFileName = base,
        partIndex = index,
        format = SplitNamingFormat.PS3_666,
      )
    }

    val numMatch = numericRegex.matchEntire(fileName)
    if (numMatch != null) {
      val base = numMatch.groupValues[1]
      val rawDigits = numMatch.groupValues[2]
      val num = rawDigits.toIntOrNull() ?: return null
      val format =
        if (num == 0) SplitNamingFormat.ZERO_BASED_000 else SplitNamingFormat.SEQUENTIAL_001
      return ParsedSplitPart(
        baseFileName = base,
        partIndex = num,
        format = format,
      )
    }

    return null
  }

  fun calculatePartCount(fileSizeBytes: Long, chunkSizeBytes: Long): Int {
    if (fileSizeBytes <= 0L) return 1
    val safeChunk = clampFat32ChunkSize(chunkSizeBytes)
    if (fileSizeBytes <= safeChunk) return 1
    return ceil(fileSizeBytes.toDouble() / safeChunk.toDouble()).toInt().coerceAtLeast(1)
  }

  fun calculatePartSizes(fileSizeBytes: Long, chunkSizeBytes: Long): List<Long> {
    if (fileSizeBytes <= 0L) return listOf(0L)
    val safeChunk = clampFat32ChunkSize(chunkSizeBytes)
    val count = calculatePartCount(fileSizeBytes, safeChunk)
    val sizes = ArrayList<Long>(count)
    var remaining = fileSizeBytes
    for (i in 0 until count) {
      val partSize = minOf(remaining, safeChunk)
      sizes.add(partSize)
      remaining -= partSize
    }
    return sizes
  }

  fun formatBytesHuman(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val kib = bytes / 1024.0
    if (kib < 1024.0) return String.format(Locale.US, "%.1f KiB", kib)
    val mib = kib / 1024.0
    if (mib < 1024.0) return String.format(Locale.US, "%.2f MiB", mib)
    val gib = mib / 1024.0
    return String.format(Locale.US, "%.2f GiB", gib)
  }

  fun formatBytesExact(bytes: Long): String =
    String.format(Locale.US, "%,d B", bytes)

  fun formatSpeed(bytesPerSec: Long): String {
    if (bytesPerSec <= 0L) return "-- MB/s"
    val mbPerSec = bytesPerSec / (1024.0 * 1024.0)
    return String.format(Locale.US, "%.1f MiB/s", mbPerSec)
  }

  fun formatDuration(millis: Long): String {
    val totalSeconds = (millis / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0) {
      String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
      String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
  }
}
