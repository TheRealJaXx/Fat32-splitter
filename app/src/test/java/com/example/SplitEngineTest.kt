package com.example

import com.example.engine.SplitNaming
import com.example.engine.StreamSplitter
import com.example.model.ChunkSizePreset
import com.example.model.SplitNamingFormat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.random.Random
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitEngineTest {

  @Test
  fun partFileNameFormatsCorrectly() {
    assertEquals(
      "BLUS30443.iso.66600",
      SplitNaming.partFileName("BLUS30443.iso", 0, SplitNamingFormat.PS3_666),
    )
    assertEquals(
      "BLUS30443.iso.66612",
      SplitNaming.partFileName("BLUS30443.iso", 12, SplitNamingFormat.PS3_666),
    )
    assertEquals(
      "archive.tar.001",
      SplitNaming.partFileName("archive.tar", 0, SplitNamingFormat.SEQUENTIAL_001),
    )
    assertEquals(
      "archive.tar.000",
      SplitNaming.partFileName("archive.tar", 0, SplitNamingFormat.ZERO_BASED_000),
    )
  }

  @Test
  fun parseSplitPartRecognizesPs3AndNumericFormats() {
    val ps3 = SplitNaming.parseSplitPart("USRDIR.psarc.66604")
    assertNotNull(ps3)
    assertEquals("USRDIR.psarc", ps3!!.baseFileName)
    assertEquals(4, ps3.partIndex)
    assertEquals(SplitNamingFormat.PS3_666, ps3.format)

    val seq = SplitNaming.parseSplitPart("video.mkv.002")
    assertNotNull(seq)
    assertEquals("video.mkv", seq!!.baseFileName)
    assertEquals(2, seq.partIndex)
    assertEquals(SplitNamingFormat.SEQUENTIAL_001, seq.format)
  }

  @Test
  fun fat32MaxClampNeverExceeds4GiBMinusOne() {
    assertEquals(ChunkSizePreset.FAT32_MAX_BYTES, SplitNaming.clampFat32ChunkSize(5_000_000_000L))
    val sizes = SplitNaming.calculatePartSizes(10_000_000_000L, ChunkSizePreset.FAT32_MAX_BYTES)
    assertEquals(3, sizes.size)
    assertEquals(10_000_000_000L, sizes.sum())
    assertTrue(sizes.all { it <= ChunkSizePreset.FAT32_MAX_BYTES })
  }

  @Test
  fun splitAndJoinRoundTripIsByteAndCrcExact() = runTest {
    val payload = Random(42).nextBytes(250_321)
    val chunkLimit = 64_000L
    val partBuffers = linkedMapOf<Int, ByteArrayOutputStream>()
    val partNames = mutableListOf<String>()

    val splitResult =
      StreamSplitter.splitStream(
        sourceName = "test_game.iso",
        sourceSizeBytes = payload.size.toLong(),
        chunkSizeBytes = chunkLimit,
        namingFormat = SplitNamingFormat.PS3_666,
        computeChecksum = true,
        bufferSize = 8_192,
        openInput = { ByteArrayInputStream(payload) },
        openPartOutput = { index, name ->
          partNames.add(name)
          ByteArrayOutputStream().also { partBuffers[index] = it }
        },
        onProgress = {},
      )

    assertEquals(4, splitResult.parts.size)
    assertEquals("test_game.iso.66600", partNames[0])
    assertEquals("test_game.iso.66603", partNames[3])
    assertTrue(splitResult.byteExact)
    assertTrue(splitResult.checksumMatched)
    assertEquals(payload.size.toLong(), splitResult.totalBytesWritten)

    val mergedOut = ByteArrayOutputStream()
    val joinResult =
      StreamSplitter.joinStreams(
        outputFileName = "test_game.iso",
        totalExpectedBytes = payload.size.toLong(),
        partNames = partNames,
        computeChecksum = true,
        bufferSize = 8_192,
        openPartInput = { idx -> ByteArrayInputStream(partBuffers.getValue(idx).toByteArray()) },
        openMergedOutput = { mergedOut },
        onProgress = {},
      )

    assertEquals(payload.size.toLong(), joinResult.totalBytesWritten)
    assertEquals(splitResult.outputCrc32Hex, joinResult.outputCrc32Hex)
    assertArrayEquals(payload, mergedOut.toByteArray())
  }
}
