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
    assertEquals(0x00800080L, LittleEndian.getU32(ntfsBoot, 0x24))
    assertEquals(1, ntfsBoot[0x44].toInt())
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

    // Verify MFT record 9 ($Secure) has $SDH (0x12) before $SII (0x10) and non-resident $SDS
    val mftAll = dev.readSectors(completed.partitionStartLba + mftCluster * spc, 48)
    val secureRec = undoUsaFixup(mftAll.copyOfRange(9 * 1024, 10 * 1024))
    assertEquals("FILE", String(secureRec, 0, 4, Charsets.US_ASCII))
    val attrs = parseMftAttributes(secureRec)
    val indexRoots = attrs.filter { it.type == 0x90 }
    assertEquals(listOf("\$SDH", "\$SII"), indexRoots.map { it.name })
    assertEquals(0x12L, LittleEndian.getU32(secureRec, indexRoots[0].bodyOffset + 4))
    assertEquals(0x10L, LittleEndian.getU32(secureRec, indexRoots[1].bodyOffset + 4))

    // Verify $Bitmap marks boot, MFT, and $SDS clusters allocated
    val rec6 = undoUsaFixup(mftAll.copyOfRange(6 * 1024, 7 * 1024))
    val bitmapAttr = parseMftAttributes(rec6).first { it.type == 0x80 }
    val bitmapFirstCluster = decodeFirstRunStartCluster(rec6, bitmapAttr.runOffset)
    val bitmapSector =
      dev.readSectors(completed.partitionStartLba + bitmapFirstCluster * spc, 1)
    assertTrue((bitmapSector[0].toInt() and 0x01) != 0) // Cluster 0 ($Boot) allocated

    val sdsAttr = attrs.first { it.type == 0x80 && it.name == "\$SDS" }
    val sdsStartCluster = decodeFirstRunStartCluster(secureRec, sdsAttr.runOffset)
    val sdsByteIdx = (sdsStartCluster / 8L).toInt()
    val sdsBitMask = 1 shl (sdsStartCluster % 8L).toInt()
    assertTrue((bitmapSector[sdsByteIdx].toInt() and sdsBitMask) != 0)

    // Verify $SDS primary (0x0) and mirror (0x40000) security descriptor entries
    val sdsPrimarySec =
      dev.readSectors(completed.partitionStartLba + sdsStartCluster * spc, 1)
    val sdsMirrorSec =
      dev.readSectors(
        completed.partitionStartLba + sdsStartCluster * spc + (0x40000L / 512L),
        1,
      )
    assertEquals(256L, LittleEndian.getU32(sdsPrimarySec, 4))
    assertEquals(0L, LittleEndian.getU64(sdsPrimarySec, 8))
    assertEquals(256L, LittleEndian.getU32(sdsMirrorSec, 4))
    assertEquals(0x40000L, LittleEndian.getU64(sdsMirrorSec, 8))
    assertEquals(LittleEndian.getU32(sdsPrimarySec, 0), LittleEndian.getU32(sdsMirrorSec, 0))
  }

  @Test
  fun formatNtfsWith64KClustersEncodes4KIndexBlockSize() = runTest {
    val dev = SparseMemoryBlockDevice(sectorSizeBytes = 512, totalSectors = 2_097_152L) // 1 GiB
    val completed =
      DriveFormatCoordinator.formatBlockDevice(
        device = dev,
        deviceTitle = "Test USB 1GB",
        filesystem = TargetFilesystem.MBR_NTFS,
        volumeLabel = "PS3BIG",
        clusterSizeBytes = 65536,
        onProgress = { _, _ -> },
      )
    val ntfsBoot = dev.readSectors(completed.partitionStartLba, 1)
    assertEquals(128, ntfsBoot[0x0D].toInt() and 0xFF) // 65536 / 512
    assertEquals(-12, ntfsBoot[0x44].toInt()) // 2^12 = 4096-byte INDX blocks
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

    val fatSize = LittleEndian.getU32(vbr, 36)
    val rootDirSec0 = dev.readSectors(completed.partitionStartLba + 32L + 2L * fatSize, 1)
    assertEquals(0x08, rootDirSec0[11].toInt() and 0xFF)
    assertEquals(0x5821, LittleEndian.getU16(rootDirSec0, 24))
  }

  @Test
  fun ntfsDataRunEncodingHandlesMultiByteRuns() {
    val runs = NtfsFormatter.encodeDataRuns(listOf(4L to 3L, 1024L to 32L))
    assertTrue(runs.isNotEmpty())
    assertEquals(0x00, runs.last().toInt() and 0xFF)
  }

  private data class ParsedAttr(
    val type: Int,
    val name: String,
    val bodyOffset: Int,
    val runOffset: Int,
  )

  private fun undoUsaFixup(record: ByteArray): ByteArray {
    val usaOff = LittleEndian.getU16(record, 0x04)
    val usaCount = LittleEndian.getU16(record, 0x06)
    for (i in 1 until usaCount) {
      val endOff = i * 512 - 2
      record[endOff] = record[usaOff + i * 2]
      record[endOff + 1] = record[usaOff + i * 2 + 1]
    }
    return record
  }

  private fun parseMftAttributes(record: ByteArray): List<ParsedAttr> {
    val list = mutableListOf<ParsedAttr>()
    var off = LittleEndian.getU16(record, 0x14)
    while (off + 4 <= record.size) {
      val type = LittleEndian.getU32(record, off).toInt()
      if (type == -1) break
      val len = LittleEndian.getU32(record, off + 4).toInt()
      val nonRes = record[off + 8].toInt() and 0xFF
      val nameLen = record[off + 9].toInt() and 0xFF
      val nameOff = LittleEndian.getU16(record, off + 10)
      val name =
        if (nameLen > 0) String(record, off + nameOff, nameLen * 2, Charsets.UTF_16LE) else ""
      val bodyOffset = if (nonRes == 0) off + LittleEndian.getU16(record, off + 20) else 0
      val runOffset = if (nonRes != 0) off + LittleEndian.getU16(record, off + 32) else 0
      list.add(ParsedAttr(type = type, name = name, bodyOffset = bodyOffset, runOffset = runOffset))
      off += len
    }
    return list
  }

  private fun decodeFirstRunStartCluster(record: ByteArray, runOff: Int): Long {
    val hdr = record[runOff].toInt() and 0xFF
    val lenBytes = hdr and 0x0F
    val offBytes = (hdr ushr 4) and 0x0F
    var cluster = 0L
    for (i in 0 until offBytes) {
      cluster = cluster or ((record[runOff + 1 + lenBytes + i].toLong() and 0xFFL) shl (i * 8))
    }
    return cluster
  }
}
