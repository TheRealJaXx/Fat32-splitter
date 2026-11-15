package com.example.format

import kotlin.random.Random

object Fat32Formatter {

  private const val RESERVED_SECTORS = 32
  private const val NUM_FATS = 2
  private const val ROOT_CLUSTER = 2L
  private const val FSINFO_SECTOR = 1
  private const val BACKUP_BOOT_SECTOR = 6

  fun sanitizeVolumeLabel(raw: String): String {
    val cleaned =
      raw
        .uppercase()
        .filter { it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-' || it == ' ' }
        .trim()
        .take(11)
    return cleaned.ifBlank { "PS3USB" }
  }

  fun computeSectorsPerCluster(partitionSectors: Long, sectorSizeBytes: Int, requestedClusterBytes: Int): Int {
    val requestedSpc = (requestedClusterBytes / sectorSizeBytes).coerceAtLeast(1)
    // Ensure cluster count stays >= 65525 for valid FAT32; step down cluster size if drive is tiny
    var spc = requestedSpc
    while (spc > 1 && (partitionSectors - RESERVED_SECTORS) / spc < 65525L) {
      spc /= 2
    }
    return spc.coerceAtLeast(1)
  }

  fun computeFatSizeSectors(
    partitionSectors: Long,
    sectorsPerCluster: Int,
    sectorSizeBytes: Int,
  ): Long {
    val entriesPerSector = sectorSizeBytes / 4L
    var fatSize = 1L
    repeat(6) {
      val dataSectors = partitionSectors - RESERVED_SECTORS - NUM_FATS * fatSize
      val clusterCount = (dataSectors / sectorsPerCluster).coerceAtLeast(1L)
      fatSize = ((clusterCount + 2L + entriesPerSector - 1L) / entriesPerSector).coerceAtLeast(1L)
    }
    return fatSize
  }

  suspend fun format(
    device: BlockDevice,
    layout: MbrPartitionLayout,
    volumeLabel: String,
    requestedClusterBytes: Int,
    onProgress: suspend (String, Float) -> Unit,
  ) {
    val sectorSize = device.sectorSizeBytes
    val startLba = layout.partitionStartLba
    val totalSectors = layout.partitionSectors
    val spc = computeSectorsPerCluster(totalSectors, sectorSize, requestedClusterBytes)
    val fatSize = computeFatSizeSectors(totalSectors, spc, sectorSize)
    val dataSectors = totalSectors - RESERVED_SECTORS - NUM_FATS * fatSize
    val totalClusters = dataSectors / spc
    require(totalClusters >= 65525L) {
      "Drive too small for FAT32 (${totalClusters} clusters; minimum is 65,525)"
    }

    val cleanLabel = sanitizeVolumeLabel(volumeLabel)
    val serial = Random.nextLong(1L, 0xFFFFFFFEL)

    onProgress("Writing FAT32 Boot Sector & FSInfo...", 0.25f)

    // Boot sector (Sector 0 & Backup Sector 6)
    val boot = ByteArray(sectorSize)
    boot[0] = 0xEB.toByte()
    boot[1] = 0x58
    boot[2] = 0x90.toByte()
    LittleEndian.asciiBytes("MSDOS5.0", 8).copyInto(boot, 3)
    LittleEndian.putU16(boot, 11, sectorSize)
    boot[13] = (spc and 0xFF).toByte()
    LittleEndian.putU16(boot, 14, RESERVED_SECTORS)
    boot[16] = NUM_FATS.toByte()
    LittleEndian.putU16(boot, 17, 0) // RootEntryCount = 0 for FAT32
    LittleEndian.putU16(boot, 19, 0) // TotSec16 = 0
    boot[21] = 0xF8.toByte() // Media = fixed/removable disk
    LittleEndian.putU16(boot, 22, 0) // FATSz16 = 0
    LittleEndian.putU16(boot, 24, 63) // Sectors per track
    LittleEndian.putU16(boot, 26, 255) // Heads
    LittleEndian.putU32(boot, 28, startLba) // Hidden sectors
    LittleEndian.putU32(boot, 32, totalSectors)
    LittleEndian.putU32(boot, 36, fatSize)
    LittleEndian.putU16(boot, 40, 0) // ExtFlags
    LittleEndian.putU16(boot, 42, 0) // FSVer 0.0
    LittleEndian.putU32(boot, 44, ROOT_CLUSTER)
    LittleEndian.putU16(boot, 48, FSINFO_SECTOR)
    LittleEndian.putU16(boot, 50, BACKUP_BOOT_SECTOR)
    boot[64] = 0x80.toByte()
    boot[66] = 0x29 // Extended boot signature
    LittleEndian.putU32(boot, 67, serial)
    LittleEndian.asciiBytes(cleanLabel, 11).copyInto(boot, 71)
    LittleEndian.asciiBytes("FAT32   ", 8).copyInto(boot, 82)
    boot[510] = 0x55
    boot[511] = 0xAA.toByte()

    // FSInfo sector (Sector 1 & Backup Sector 7)
    val fsInfo = ByteArray(sectorSize)
    LittleEndian.putU32(fsInfo, 0, 0x41615252L) // RRaA
    LittleEndian.putU32(fsInfo, 484, 0x61417272L) // rrAa
    LittleEndian.putU32(fsInfo, 488, (totalClusters - 1L).coerceAtLeast(0L))
    LittleEndian.putU32(fsInfo, 492, 3L) // Next free cluster
    LittleEndian.putU32(fsInfo, 508, 0xAA550000L)

    // Reserved region: zero sectors 0..31 then place boot + fsInfo + backups
    val reservedRegion = ByteArray(RESERVED_SECTORS * sectorSize)
    boot.copyInto(reservedRegion, 0)
    fsInfo.copyInto(reservedRegion, FSINFO_SECTOR * sectorSize)
    boot.copyInto(reservedRegion, BACKUP_BOOT_SECTOR * sectorSize)
    fsInfo.copyInto(reservedRegion, (BACKUP_BOOT_SECTOR + 1) * sectorSize)
    device.writeSectors(startLba, reservedRegion)

    // Zero FAT1 + FAT2 in chunks, writing FAT head entries at sector 0 of each FAT
    val fat1Start = startLba + RESERVED_SECTORS
    val fat2Start = fat1Start + fatSize
    val fatHead = ByteArray(sectorSize)
    LittleEndian.putU32(fatHead, 0, 0x0FFFFFF8L)
    LittleEndian.putU32(fatHead, 4, 0x0FFFFFFFL)
    LittleEndian.putU32(fatHead, 8, 0x0FFFFFFFL) // Root directory EOC

    val zeroChunkSectors = 256 // 128 KiB per write
    val zeroBuf = ByteArray(zeroChunkSectors * sectorSize)

    for (fatIdx in 0 until NUM_FATS) {
      val currentFatStart = if (fatIdx == 0) fat1Start else fat2Start
      var offset = 0L
      while (offset < fatSize) {
        val count = minOf(zeroChunkSectors.toLong(), fatSize - offset).toInt()
        val payload = if (count == zeroChunkSectors) zeroBuf else ByteArray(count * sectorSize)
        device.writeSectors(currentFatStart + offset, payload)
        offset += count
        val basePct = 0.30f + 0.55f * ((fatIdx * fatSize + offset).toFloat() / (NUM_FATS * fatSize).toFloat())
        onProgress("Initializing FAT${fatIdx + 1} allocation table...", basePct.coerceIn(0.30f, 0.85f))
      }
      device.writeSectors(currentFatStart, fatHead)
    }

    onProgress("Writing FAT32 Root Directory...", 0.92f)
    val rootDirStart = fat1Start + NUM_FATS * fatSize
    val rootDirCluster = ByteArray(spc * sectorSize)
    // Volume label directory entry (attribute 0x08)
    LittleEndian.asciiBytes(cleanLabel, 11).copyInto(rootDirCluster, 0)
    rootDirCluster[11] = 0x08
    device.writeSectors(rootDirStart, rootDirCluster)

    onProgress("Flushing USB cache...", 0.98f)
    device.syncCache()
  }
}
