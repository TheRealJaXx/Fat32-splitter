package com.example

import com.example.format.BlockDevice
import com.example.format.DriveFormatCoordinator
import com.example.format.LittleEndian
import com.example.format.MbrWriter
import com.example.format.NtfsFormatter
import com.example.format.PartitionScheme
import com.example.format.TargetFilesystem
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class SparseMemoryBlockDevice(
  override val sectorSizeBytes: Int = 512,
  override val totalSectors: Long = 1_048_576L, // 512 MiB
) : BlockDevice {
  private val sectors = HashMap<Long, ByteArray>()

  override suspend fun readSectors(startLba: Long, sectorCount: Int): ByteArray {
    val out = ByteArray(sectorCount * sectorSizeBytes)
    for (i in 0 until sectorCount) {
      val s = sectors[startLba + i.toLong()]
      if (s != null) {
        s.copyInto(out, i * sectorSizeBytes)
      }
    }
    return out
  }

  override suspend fun writeSectors(startLba: Long, data: ByteArray) {
    require(data.size % sectorSizeBytes == 0)
    val count = data.size / sectorSizeBytes
    for (i in 0 until count) {
      sectors[startLba + i.toLong()] =
        data.copyOfRange(i * sectorSizeBytes, (i + 1) * sectorSizeBytes)
    }
  }

  override suspend fun syncCache() {}

  override fun close() {}
}

class DriveFormatterTest {

  @Test
  fun formatGptDriveToMbrNtfsWipesGptAndWritesValidNtfsStructures() = runTest {
    val dev = SparseMemoryBlockDevice(sectorSizeBytes = 512, totalSectors = 1_048_576L)

    // Seed primary and backup GPT headers to verify cleanup
    val gptHeader = ByteArray(512)
    LittleEndian.asciiBytes("EFI PART", 8).copyInto(gptHeader, 0)
    dev.writeSectors(1L, gptHeader)
    dev.writeSectors(dev.totalSectors - 1L, gptHeader)

    val mbrProtective = ByteArray(512)
    mbrProtective[0x1BE + 4] = 0xEE.toByte()
    LittleEndian.putU32(mbrProtective, 0x1BE + 12, 0xFFFFFFFFL)
    mbrProtective[510] = 0x55
    mbrProtective[511] = 0xAA.toByte()
    dev.writeSectors(0L, mbrProtective)

    assertEquals(PartitionScheme.GPT, MbrWriter.inspectPartitionScheme(dev.readSectors(0, 1), dev.readSectors(1, 1)))

    val completed =
      DriveFormatCoordinator.formatBlockDevice(
        device = dev,
        deviceTitle = "Test USB 512MB",
        filesystem = TargetFilesystem.MBR_NTFS,
        volumeLabel = "PS3GAMES",
        clusterSizeBytes = 4096,
        onProgress = { _, _ -> },
      )

    assertEquals("PS3GAMES", completed.volumeLabel)
    assertEquals(2048L, completed.partitionStartLba)

    // Verify MBR Sector 0 and cleaned primary/backup GPT
    val sec0 = dev.readSectors(0L, 1)
    val sec1 = dev.readSectors(1L, 1)
    val tailSec = dev.readSectors(dev.totalSectors - 1L, 1)
    assertEquals(PartitionScheme.MBR, MbrWriter.inspectPartitionScheme(sec0, sec1))
    assertEquals(0x07, sec0[0x1BE + 4].toInt() and 0xFF)
    assertEquals(2048L, LittleEndian.getU32(sec0, 0x1BE + 8))
    assertNotEquals("EFI PART", String(tailSec, 0, 8, Charsets.US_ASCII))

    // Verify NTFS Boot Sector and Backup Boot Sector
    val ntfsBoot = dev.readSectors(completed.partitionStartLba, 1)
    val ntfsBackupBoot =
      dev.readSectors(completed.partitionStartLba + completed.partitionSectors - 1L, 1)
    assertEquals("NTFS    ", String(ntfsBoot, 3, 8, Charsets.US_ASCII))
    assertEquals(512, LittleEndian.getU16(ntfsBoot, 0x0B))
    assertEquals(8, ntfsBoot[0x0D].toInt() and 0xFF)
    assertEquals(completed.partitionSectors - 1L, LittleEndian.getU64(ntfsBoot, 0x28))
    assertArrayEquals(ntfsBoot, ntfsBackupBoot)

    // Verify $MFT and $MFTMirr match on records 0..3 and carry "FILE" magic + USA fixup
    val mftCluster = LittleEndian.getU64(ntfsBoot, 0x30)
    val mftMirrCluster = LittleEndian.getU64(ntfsBoot, 0x38)
    val spc = ntfsBoot[0x0D].toInt() and 0xFF
    val mftHead = dev.readSectors(completed.partitionStartLba + mftCluster * spc, 8)
    val mftMirr = dev.readSectors(completed.partitionStartLba + mftMirrCluster * spc, 8)
    assertEquals("FILE", String(mftHead, 0, 4, Charsets.US_ASCII))
    assertEquals("FILE", String(mftHead, 1024, 4, Charsets.US_ASCII))
    assertEquals(1, LittleEndian.getU16(mftHead, 510)) // USA fixup applied at end of sector 0
    assertArrayEquals(mftHead, mftMirr)
  }

  @Test
  fun formatDriveToMbrFat32WritesValidBpbFsInfoAndFatTables() = runTest {
    val dev = SparseMemoryBlockDevice(sectorSizeBytes = 512, totalSectors = 1_048_576L)

    val completed =
      DriveFormatCoordinator.formatBlockDevice(
        device = dev,
        deviceTitle = "Test USB FAT32",
        filesystem = TargetFilesystem.MBR_FAT32,
        volumeLabel = "ps3_fat32!",
        clusterSizeBytes = 4096,
        onProgress = { _, _ -> },
      )

    assertEquals("PS3_FAT32", completed.volumeLabel)
    val sec0 = dev.readSectors(0L, 1)
    assertEquals(0x0C, sec0[0x1BE + 4].toInt() and 0xFF)

    val vbr = dev.readSectors(completed.partitionStartLba, 1)
    val backupVbr = dev.readSectors(completed.partitionStartLba + 6L, 1)
    assertEquals("MSDOS5.0", String(vbr, 3, 8, Charsets.US_ASCII))
    assertEquals("FAT32   ", String(vbr, 82, 8, Charsets.US_ASCII))
    assertArrayEquals(vbr, backupVbr)

    val fsInfo = dev.readSectors(completed.partitionStartLba + 1L, 1)
    assertEquals(0x41615252L, LittleEndian.getU32(fsInfo, 0))
    assertEquals(0x61417272L, LittleEndian.getU32(fsInfo, 484))
    assertEquals(0xAA550000L, LittleEndian.getU32(fsInfo, 508))

    val fat1Sec0 = dev.readSectors(completed.partitionStartLba + 32L, 1)
    assertEquals(0x0FFFFFF8L, LittleEndian.getU32(fat1Sec0, 0))
    assertEquals(0x0FFFFFFFL, LittleEndian.getU32(fat1Sec0, 4))
    assertEquals(0x0FFFFFFFL, LittleEndian.getU32(fat1Sec0, 8))
  }

  @Test
  fun ntfsDataRunEncodingHandlesMultiByteRuns() {
    val runs = NtfsFormatter.encodeDataRuns(listOf(4L to 3L, 1024L to 32L))
    assertTrue(runs.isNotEmpty())
    assertEquals(0x00, runs.last().toInt() and 0xFF)
  }
}
