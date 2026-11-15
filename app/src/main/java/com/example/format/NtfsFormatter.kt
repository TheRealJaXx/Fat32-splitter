package com.example.format

import kotlin.math.ceil
import kotlin.random.Random

/**
 * Pure Kotlin NTFS 3.1 Quick Formatter for MBR partition layouts.
 * Generates spec-compliant $Boot (+ backup), $MFT, $MFTMirr, $LogFile, $Volume,
 * $AttrDef, Root INDX ($I30), $Bitmap, $BadClus, $Secure, $UpCase, and $Extend.
 */
object NtfsFormatter {

  private const val MFT_RECORD_SIZE = 1024
  private const val NTFS_USA_STRIDE = 512
  private const val INDEX_BLOCK_SIZE = 4096
  private const val MFT_SYSTEM_RECORDS = 16
  private const val MFT_TOTAL_INIT_RECORDS = 24

  // Standard NTFS Windows FileTime epoch offset (1601-01-01 to 1970-01-01 in 100ns ticks)
  private const val EPOCH_DIFF_100NS = 116_444_736_000_000_000L

  fun sanitizeVolumeLabel(raw: String): String =
    raw.trim().take(32).ifBlank { "PS3USB" }

  suspend fun format(
    device: BlockDevice,
    layout: MbrPartitionLayout,
    volumeLabel: String,
    requestedClusterBytes: Int,
    onProgress: suspend (String, Float) -> Unit,
  ) {
    val sectorSize = device.sectorSizeBytes
    val clusterBytes = requestedClusterBytes.coerceIn(maxOf(sectorSize, 4096), 65536)
    val sectorsPerCluster = (clusterBytes / sectorSize).coerceAtLeast(1)
    val partStart = layout.partitionStartLba
    val partSectors = layout.partitionSectors
    // NTFS volume totalSectors excludes the final backup boot sector
    val ntfsTotalSectors = partSectors - 1L
    val totalClusters = ntfsTotalSectors / sectorsPerCluster
    require(totalClusters >= 512L) {
      "Partition too small for NTFS ($totalClusters clusters)"
    }

    val cleanLabel = sanitizeVolumeLabel(volumeLabel)
    val nowFileTime = System.currentTimeMillis() * 10_000L + EPOCH_DIFF_100NS
    val serial64 = Random.nextLong()

    // Cluster allocations (all contiguous runs for clean sequential writes)
    // Cluster 0..bootClusters-1: $Boot (at least 8192 bytes)
    val bootClusters = ((8192 + clusterBytes - 1) / clusterBytes).toLong()
    val mftCluster = maxOf(4L, bootClusters)
    val mftBytes = MFT_TOTAL_INIT_RECORDS * MFT_RECORD_SIZE // 24 KiB
    val mftClusters = ((mftBytes + clusterBytes - 1) / clusterBytes).toLong()

    val logFileBytes = 64 * 1024 // 64 KiB cleanly initialized log
    val logFileClusters = ((logFileBytes + clusterBytes - 1) / clusterBytes).toLong()
    val logFileCluster = mftCluster + mftClusters

    val attrDefBytes = 16 * 160 // 2560 bytes
    val attrDefClusters = ((attrDefBytes + clusterBytes - 1) / clusterBytes).toLong()
    val attrDefCluster = logFileCluster + logFileClusters

    val rootIndexClusters = ((INDEX_BLOCK_SIZE + clusterBytes - 1) / clusterBytes).toLong()
    val rootIndexCluster = attrDefCluster + attrDefClusters

    val bitmapBytes = ((totalClusters + 7L) / 8L).toInt()
    val bitmapClusters = ((bitmapBytes + clusterBytes - 1) / clusterBytes).toLong()
    val bitmapCluster = rootIndexCluster + rootIndexClusters

    val upCaseBytes = 65536 * 2 // 128 KiB
    val upCaseClusters = ((upCaseBytes + clusterBytes - 1) / clusterBytes).toLong()
    val upCaseCluster = bitmapCluster + bitmapClusters

    val mftMirrClusters = ((4 * MFT_RECORD_SIZE + clusterBytes - 1) / clusterBytes).toLong()
    val mftMirrCluster = maxOf(upCaseCluster + upCaseClusters + 8L, totalClusters / 2L)
    require(mftMirrCluster + mftMirrClusters < totalClusters) {
      "Drive geometry cannot accommodate NTFS MFT mirror"
    }

    onProgress("Building NTFS Boot Sector (\$Boot)...", 0.22f)
    val bootSector =
      buildBootSector(
        sectorSize = sectorSize,
        sectorsPerCluster = sectorsPerCluster,
        clusterBytes = clusterBytes,
        hiddenSectors = partStart,
        ntfsTotalSectors = ntfsTotalSectors,
        mftCluster = mftCluster,
        mftMirrCluster = mftMirrCluster,
        serial64 = serial64,
      )

    // Write $Boot (8192 bytes padded to cluster boundary) & backup boot sector at partition end
    val bootArea = ByteArray((bootClusters * clusterBytes).toInt())
    bootSector.copyInto(bootArea, 0)
    device.writeSectors(partStart, bootArea)
    device.writeSectors(partStart + partSectors - 1L, bootSector)

    // Build $Root INDX block (4096 bytes) listing system metadata files
    onProgress("Building NTFS Root Directory & Metadata...", 0.35f)
    val rootEntries =
      listOf(
        IndexFileMeta(4L, 1, "\$AttrDef", nowFileTime, attrDefBytes.toLong(), attrDefClusters * clusterBytes, 0x06),
        IndexFileMeta(8L, 1, "\$BadClus", nowFileTime, 0L, 0L, 0x06),
        IndexFileMeta(6L, 1, "\$Bitmap", nowFileTime, bitmapBytes.toLong(), bitmapClusters * clusterBytes, 0x06),
        IndexFileMeta(7L, 1, "\$Boot", nowFileTime, 8192L, bootClusters * clusterBytes, 0x06),
        IndexFileMeta(11L, 1, "\$Extend", nowFileTime, 0L, 0L, 0x10000006),
        IndexFileMeta(2L, 1, "\$LogFile", nowFileTime, logFileBytes.toLong(), logFileClusters * clusterBytes, 0x06),
        IndexFileMeta(0L, 1, "\$MFT", nowFileTime, mftBytes.toLong(), mftClusters * clusterBytes, 0x06),
        IndexFileMeta(1L, 1, "\$MFTMirr", nowFileTime, 4096L, mftMirrClusters * clusterBytes, 0x06),
        IndexFileMeta(9L, 1, "\$Secure", nowFileTime, 0L, 0L, 0x06),
        IndexFileMeta(10L, 1, "\$UpCase", nowFileTime, upCaseBytes.toLong(), upCaseClusters * clusterBytes, 0x06),
        IndexFileMeta(3L, 1, "\$Volume", nowFileTime, 0L, 0L, 0x06),
      ).sortedWith { a, b -> compareNtfsNames(a.name, b.name) }

    val rootIndxBlock = buildRootIndxBlock(rootEntries)

    // Build 24 MFT records (0..15 initialized, 16..23 free placeholders)
    val mftArea = ByteArray((mftClusters * clusterBytes).toInt())
    val mftBitmap = ByteArray(8)
    mftBitmap[0] = 0xFF.toByte() // Records 0..7 in use
    mftBitmap[1] = 0xFF.toByte() // Records 8..15 in use

    val mftRec0 =
      buildMftRecord0(
        nowFileTime = nowFileTime,
        mftCluster = mftCluster,
        mftClusters = mftClusters,
        mftDataSize = mftBytes.toLong(),
        mftBitmap = mftBitmap,
      )
    val mftRec1 =
      buildSimpleNonResidentRecord(
        recordNum = 1,
        seqNum = 1,
        fileName = "\$MFTMirr",
        nowFileTime = nowFileTime,
        startCluster = mftMirrCluster,
        clusterCount = mftMirrClusters,
        dataSize = 4096L,
        allocatedSize = mftMirrClusters * clusterBytes,
      )
    val mftRec2 =
      buildSimpleNonResidentRecord(
        recordNum = 2,
        seqNum = 1,
        fileName = "\$LogFile",
        nowFileTime = nowFileTime,
        startCluster = logFileCluster,
        clusterCount = logFileClusters,
        dataSize = logFileBytes.toLong(),
        allocatedSize = logFileClusters * clusterBytes,
      )
    val mftRec3 = buildVolumeRecord(nowFileTime, cleanLabel)
    val mftRec4 =
      buildSimpleNonResidentRecord(
        recordNum = 4,
        seqNum = 1,
        fileName = "\$AttrDef",
        nowFileTime = nowFileTime,
        startCluster = attrDefCluster,
        clusterCount = attrDefClusters,
        dataSize = attrDefBytes.toLong(),
        allocatedSize = attrDefClusters * clusterBytes,
      )
    val mftRec5 =
      buildRootDirRecord(
        nowFileTime = nowFileTime,
        indexAllocCluster = rootIndexCluster,
        indexAllocClusters = rootIndexClusters,
        clusterBytes = clusterBytes,
      )
    val mftRec6 =
      buildSimpleNonResidentRecord(
        recordNum = 6,
        seqNum = 1,
        fileName = "\$Bitmap",
        nowFileTime = nowFileTime,
        startCluster = bitmapCluster,
        clusterCount = bitmapClusters,
        dataSize = bitmapBytes.toLong(),
        allocatedSize = bitmapClusters * clusterBytes,
      )
    val mftRec7 =
      buildSimpleNonResidentRecord(
        recordNum = 7,
        seqNum = 1,
        fileName = "\$Boot",
        nowFileTime = nowFileTime,
        startCluster = 0L,
        clusterCount = bootClusters,
        dataSize = 8192L,
        allocatedSize = bootClusters * clusterBytes,
      )
    val mftRec8 = buildBadClusRecord(nowFileTime)
    val mftRec9 = buildSecureRecord(nowFileTime)
    val mftRec10 =
      buildSimpleNonResidentRecord(
        recordNum = 10,
        seqNum = 1,
        fileName = "\$UpCase",
        nowFileTime = nowFileTime,
        startCluster = upCaseCluster,
        clusterCount = upCaseClusters,
        dataSize = upCaseBytes.toLong(),
        allocatedSize = upCaseClusters * clusterBytes,
      )
    val mftRec11 = buildExtendRecord(nowFileTime)

    val records = listOf(mftRec0, mftRec1, mftRec2, mftRec3, mftRec4, mftRec5, mftRec6, mftRec7, mftRec8, mftRec9, mftRec10, mftRec11)
    records.forEachIndexed { idx, rec -> rec.copyInto(mftArea, idx * MFT_RECORD_SIZE) }
    for (idx in 12 until MFT_SYSTEM_RECORDS) {
      buildEmptyRecord(idx, inUse = true).copyInto(mftArea, idx * MFT_RECORD_SIZE)
    }
    for (idx in MFT_SYSTEM_RECORDS until MFT_TOTAL_INIT_RECORDS) {
      buildEmptyRecord(idx, inUse = false).copyInto(mftArea, idx * MFT_RECORD_SIZE)
    }

    onProgress("Writing \$MFT & \$MFTMirr...", 0.48f)
    device.writeSectors(partStart + mftCluster * sectorsPerCluster, mftArea)

    val mftMirrorArea = ByteArray((mftMirrClusters * clusterBytes).toInt())
    mftArea.copyInto(mftMirrorArea, 0, 0, minOf(4 * MFT_RECORD_SIZE, mftMirrorArea.size))
    device.writeSectors(partStart + mftMirrCluster * sectorsPerCluster, mftMirrorArea)

    onProgress("Writing \$LogFile, \$AttrDef & Root Index...", 0.62f)
    val logArea = ByteArray((logFileClusters * clusterBytes).toInt()) { 0xFF.toByte() }
    device.writeSectors(partStart + logFileCluster * sectorsPerCluster, logArea)

    val attrDefArea = ByteArray((attrDefClusters * clusterBytes).toInt())
    buildAttrDefTable().copyInto(attrDefArea, 0)
    device.writeSectors(partStart + attrDefCluster * sectorsPerCluster, attrDefArea)

    val rootIdxArea = ByteArray((rootIndexClusters * clusterBytes).toInt())
    rootIndxBlock.copyInto(rootIdxArea, 0)
    device.writeSectors(partStart + rootIndexCluster * sectorsPerCluster, rootIdxArea)

    onProgress("Writing Cluster \$Bitmap...", 0.78f)
    val bitmapArea = ByteArray((bitmapClusters * clusterBytes).toInt())
    markClustersAllocated(bitmapArea, 0L, bootClusters)
    markClustersAllocated(bitmapArea, mftCluster, mftClusters)
    markClustersAllocated(bitmapArea, logFileCluster, logFileClusters)
    markClustersAllocated(bitmapArea, attrDefCluster, attrDefClusters)
    markClustersAllocated(bitmapArea, rootIndexCluster, rootIndexClusters)
    markClustersAllocated(bitmapArea, bitmapCluster, bitmapClusters)
    markClustersAllocated(bitmapArea, upCaseCluster, upCaseClusters)
    markClustersAllocated(bitmapArea, mftMirrCluster, mftMirrClusters)
    writeChunked(device, partStart + bitmapCluster * sectorsPerCluster, bitmapArea, sectorSize)

    onProgress("Writing Unicode \$UpCase Table...", 0.90f)
    val upCaseArea = ByteArray((upCaseClusters * clusterBytes).toInt())
    buildUpCaseTable().copyInto(upCaseArea, 0)
    writeChunked(device, partStart + upCaseCluster * sectorsPerCluster, upCaseArea, sectorSize)

    onProgress("Flushing USB cache...", 0.98f)
    device.syncCache()
  }

  private suspend fun writeChunked(
    device: BlockDevice,
    startLba: Long,
    data: ByteArray,
    sectorSize: Int,
  ) {
    val maxChunkBytes = 64 * 1024
    var offset = 0
    while (offset < data.size) {
      val len = minOf(maxChunkBytes, data.size - offset)
      val slice = data.copyOfRange(offset, offset + len)
      device.writeSectors(startLba + (offset / sectorSize), slice)
      offset += len
    }
  }

  private fun markClustersAllocated(bitmap: ByteArray, startCluster: Long, count: Long) {
    for (c in startCluster until (startCluster + count)) {
      val byteIdx = (c / 8L).toInt()
      val bitIdx = (c % 8L).toInt()
      if (byteIdx in bitmap.indices) {
        bitmap[byteIdx] = (bitmap[byteIdx].toInt() or (1 shl bitIdx)).toByte()
      }
    }
  }

  private fun buildBootSector(
    sectorSize: Int,
    sectorsPerCluster: Int,
    clusterBytes: Int,
    hiddenSectors: Long,
    ntfsTotalSectors: Long,
    mftCluster: Long,
    mftMirrCluster: Long,
    serial64: Long,
  ): ByteArray {
    val boot = ByteArray(sectorSize)
    boot[0] = 0xEB.toByte()
    boot[1] = 0x52
    boot[2] = 0x90.toByte()
    LittleEndian.asciiBytes("NTFS    ", 8).copyInto(boot, 3)
    LittleEndian.putU16(boot, 0x0B, sectorSize)
    boot[0x0D] = (sectorsPerCluster and 0xFF).toByte()
    boot[0x15] = 0xF8.toByte()
    LittleEndian.putU16(boot, 0x18, 63)
    LittleEndian.putU16(boot, 0x1A, 255)
    LittleEndian.putU32(boot, 0x1C, hiddenSectors)
    LittleEndian.putU32(boot, 0x24, 0x80008000L)
    LittleEndian.putU64(boot, 0x28, ntfsTotalSectors)
    LittleEndian.putU64(boot, 0x30, mftCluster)
    LittleEndian.putU64(boot, 0x38, mftMirrCluster)
    boot[0x40] = (-10).toByte() // 2^10 = 1024 bytes per MFT record
    boot[0x44] = if (clusterBytes >= INDEX_BLOCK_SIZE) 1 else (-12).toByte()
    LittleEndian.putU64(boot, 0x48, serial64)
    boot[510] = 0x55
    boot[511] = 0xAA.toByte()
    return boot
  }

  private fun applyUsaFixup(block: ByteArray, usaOffset: Int, usaCount: Int, usn: Int = 1) {
    LittleEndian.putU16(block, usaOffset, usn)
    for (i in 1 until usaCount) {
      val blockEndOffset = i * NTFS_USA_STRIDE - 2
      val origLo = block[blockEndOffset]
      val origHi = block[blockEndOffset + 1]
      block[usaOffset + i * 2] = origLo
      block[usaOffset + i * 2 + 1] = origHi
      LittleEndian.putU16(block, blockEndOffset, usn)
    }
  }

  private fun initMftHeader(
    recordNum: Int,
    seqNum: Int,
    flags: Int,
    linkCount: Int = 1,
  ): ByteArray {
    val rec = ByteArray(MFT_RECORD_SIZE)
    LittleEndian.asciiBytes("FILE", 4).copyInto(rec, 0x00)
    LittleEndian.putU16(rec, 0x04, 0x30) // USA offset
    LittleEndian.putU16(rec, 0x06, 1 + (MFT_RECORD_SIZE / NTFS_USA_STRIDE)) // 3
    LittleEndian.putU16(rec, 0x10, seqNum)
    LittleEndian.putU16(rec, 0x12, linkCount)
    LittleEndian.putU16(rec, 0x14, 0x38) // First attribute offset (56)
    LittleEndian.putU16(rec, 0x16, flags)
    LittleEndian.putU32(rec, 0x1C, MFT_RECORD_SIZE.toLong())
    LittleEndian.putU32(rec, 0x2C, recordNum.toLong())
    return rec
  }

  private fun finalizeMftRecord(rec: ByteArray, endOffset: Int, nextAttrId: Int): ByteArray {
    LittleEndian.putU32(rec, endOffset, 0xFFFFFFFFL)
    val used = align8(endOffset + 4)
    LittleEndian.putU32(rec, 0x18, used.toLong())
    LittleEndian.putU16(rec, 0x28, nextAttrId)
    applyUsaFixup(rec, usaOffset = 0x30, usaCount = 3)
    return rec
  }

  private fun buildEmptyRecord(recordNum: Int, inUse: Boolean): ByteArray {
    val rec = initMftHeader(recordNum, seqNum = 1, flags = if (inUse) 0x0001 else 0x0000, linkCount = 0)
    return finalizeMftRecord(rec, 0x38, nextAttrId = 0)
  }

  private fun appendResidentAttr(
    rec: ByteArray,
    offset: Int,
    type: Int,
    attrId: Int,
    nameUtf16: String? = null,
    body: ByteArray,
  ): Int {
    val nameBytes = nameUtf16?.let { LittleEndian.utf16LeBytes(it) } ?: ByteArray(0)
    val nameLenChars = nameUtf16?.length ?: 0
    val nameOffset = 24
    val valueOffset = align8(nameOffset + nameBytes.size)
    val totalLen = align8(valueOffset + body.size)

    LittleEndian.putU32(rec, offset, type.toLong())
    LittleEndian.putU32(rec, offset + 4, totalLen.toLong())
    rec[offset + 8] = 0 // Resident
    rec[offset + 9] = nameLenChars.toByte()
    LittleEndian.putU16(rec, offset + 10, if (nameLenChars > 0) nameOffset else 0)
    LittleEndian.putU16(rec, offset + 12, 0)
    LittleEndian.putU16(rec, offset + 14, attrId)
    LittleEndian.putU32(rec, offset + 16, body.size.toLong())
    LittleEndian.putU16(rec, offset + 20, valueOffset)
    if (nameBytes.isNotEmpty()) nameBytes.copyInto(rec, offset + nameOffset)
    body.copyInto(rec, offset + valueOffset)
    return offset + totalLen
  }

  private fun appendNonResidentAttr(
    rec: ByteArray,
    offset: Int,
    type: Int,
    attrId: Int,
    nameUtf16: String? = null,
    runs: List<Pair<Long, Long>>,
    allocatedSize: Long,
    dataSize: Long,
    initializedSize: Long = dataSize,
  ): Int {
    val nameBytes = nameUtf16?.let { LittleEndian.utf16LeBytes(it) } ?: ByteArray(0)
    val nameLenChars = nameUtf16?.length ?: 0
    val nameOffset = 64
    val runOffset = align8(nameOffset + nameBytes.size)
    val encodedRuns = encodeDataRuns(runs)
    val totalLen = align8(runOffset + encodedRuns.size)

    val lastVcn =
      if (runs.isEmpty()) 0L else (runs.sumOf { it.second } - 1L).coerceAtLeast(0L)

    LittleEndian.putU32(rec, offset, type.toLong())
    LittleEndian.putU32(rec, offset + 4, totalLen.toLong())
    rec[offset + 8] = 1 // Non-resident
    rec[offset + 9] = nameLenChars.toByte()
    LittleEndian.putU16(rec, offset + 10, if (nameLenChars > 0) nameOffset else 0)
    LittleEndian.putU16(rec, offset + 12, 0)
    LittleEndian.putU16(rec, offset + 14, attrId)
    LittleEndian.putU64(rec, offset + 16, 0L) // Start VCN
    LittleEndian.putU64(rec, offset + 24, lastVcn)
    LittleEndian.putU16(rec, offset + 32, runOffset)
    LittleEndian.putU16(rec, offset + 34, 0) // Compression unit
    LittleEndian.putU64(rec, offset + 40, allocatedSize)
    LittleEndian.putU64(rec, offset + 48, dataSize)
    LittleEndian.putU64(rec, offset + 56, initializedSize)
    if (nameBytes.isNotEmpty()) nameBytes.copyInto(rec, offset + nameOffset)
    encodedRuns.copyInto(rec, offset + runOffset)
    return offset + totalLen
  }

  private fun standardInfoBody(nowFileTime: Long, dosAttrs: Int): ByteArray {
    val body = ByteArray(72)
    LittleEndian.putU64(body, 0, nowFileTime) // Creation
    LittleEndian.putU64(body, 8, nowFileTime) // Modified
    LittleEndian.putU64(body, 16, nowFileTime) // MFT Modified
    LittleEndian.putU64(body, 24, nowFileTime) // Accessed
    LittleEndian.putU32(body, 32, dosAttrs.toLong())
    LittleEndian.putU32(body, 52, 256L) // Default SecurityId
    return body
  }

  private fun fileNameBody(
    parentRecord: Long,
    parentSeq: Int,
    name: String,
    nowFileTime: Long,
    dataSize: Long,
    allocatedSize: Long,
    fileFlags: Int,
  ): ByteArray {
    val nameBytes = LittleEndian.utf16LeBytes(name)
    val body = ByteArray(66 + nameBytes.size)
    val parentRef = (parentSeq.toLong() shl 48) or (parentRecord and 0x0000FFFFFFFFFFFFL)
    LittleEndian.putU64(body, 0, parentRef)
    LittleEndian.putU64(body, 8, nowFileTime)
    LittleEndian.putU64(body, 16, nowFileTime)
    LittleEndian.putU64(body, 24, nowFileTime)
    LittleEndian.putU64(body, 32, nowFileTime)
    LittleEndian.putU64(body, 40, allocatedSize)
    LittleEndian.putU64(body, 48, dataSize)
    LittleEndian.putU32(body, 56, fileFlags.toLong())
    body[64] = name.length.toByte()
    body[65] = 3 // WIN32_AND_DOS namespace
    nameBytes.copyInto(body, 66)
    return body
  }

  private fun buildMftRecord0(
    nowFileTime: Long,
    mftCluster: Long,
    mftClusters: Long,
    mftDataSize: Long,
    mftBitmap: ByteArray,
  ): ByteArray {
    val rec = initMftHeader(0, seqNum = 1, flags = 0x0001)
    var off = 0x38
    off = appendResidentAttr(rec, off, 0x10, 0, body = standardInfoBody(nowFileTime, 0x06))
    off =
      appendResidentAttr(
        rec,
        off,
        0x30,
        1,
        body = fileNameBody(5L, 5, "\$MFT", nowFileTime, mftDataSize, mftDataSize, 0x06),
      )
    off =
      appendNonResidentAttr(
        rec,
        off,
        0x80,
        2,
        runs = listOf(mftCluster to mftClusters),
        allocatedSize = mftDataSize,
        dataSize = mftDataSize,
      )
    off = appendResidentAttr(rec, off, 0xB0, 3, body = mftBitmap)
    return finalizeMftRecord(rec, off, nextAttrId = 4)
  }

  private fun buildSimpleNonResidentRecord(
    recordNum: Int,
    seqNum: Int,
    fileName: String,
    nowFileTime: Long,
    startCluster: Long,
    clusterCount: Long,
    dataSize: Long,
    allocatedSize: Long,
  ): ByteArray {
    val rec = initMftHeader(recordNum, seqNum = seqNum, flags = 0x0001)
    var off = 0x38
    off = appendResidentAttr(rec, off, 0x10, 0, body = standardInfoBody(nowFileTime, 0x06))
    off =
      appendResidentAttr(
        rec,
        off,
        0x30,
        1,
        body = fileNameBody(5L, 5, fileName, nowFileTime, dataSize, allocatedSize, 0x06),
      )
    off =
      appendNonResidentAttr(
        rec,
        off,
        0x80,
        2,
        runs = listOf(startCluster to clusterCount),
        allocatedSize = allocatedSize,
        dataSize = dataSize,
      )
    return finalizeMftRecord(rec, off, nextAttrId = 3)
  }

  private fun buildVolumeRecord(nowFileTime: Long, volumeLabel: String): ByteArray {
    val rec = initMftHeader(3, seqNum = 1, flags = 0x0001)
    var off = 0x38
    off = appendResidentAttr(rec, off, 0x10, 0, body = standardInfoBody(nowFileTime, 0x06))
    off =
      appendResidentAttr(
        rec,
        off,
        0x30,
        1,
        body = fileNameBody(5L, 5, "\$Volume", nowFileTime, 0L, 0L, 0x06),
      )
    off = appendResidentAttr(rec, off, 0x60, 2, body = LittleEndian.utf16LeBytes(volumeLabel))
    val volInfo = ByteArray(12)
    volInfo[8] = 3 // NTFS major 3
    volInfo[9] = 1 // NTFS minor 1 (3.1)
    LittleEndian.putU16(volInfo, 10, 0) // Clean flags
    off = appendResidentAttr(rec, off, 0x70, 3, body = volInfo)
    return finalizeMftRecord(rec, off, nextAttrId = 4)
  }

  private fun buildBadClusRecord(nowFileTime: Long): ByteArray {
    val rec = initMftHeader(8, seqNum = 1, flags = 0x0001)
    var off = 0x38
    off = appendResidentAttr(rec, off, 0x10, 0, body = standardInfoBody(nowFileTime, 0x06))
    off =
      appendResidentAttr(
        rec,
        off,
        0x30,
        1,
        body = fileNameBody(5L, 5, "\$BadClus", nowFileTime, 0L, 0L, 0x06),
      )
    off = appendResidentAttr(rec, off, 0x80, 2, body = ByteArray(0))
    return finalizeMftRecord(rec, off, nextAttrId = 3)
  }

  private fun buildSecureRecord(nowFileTime: Long): ByteArray {
    val rec = initMftHeader(9, seqNum = 1, flags = 0x0001)
    var off = 0x38
    off = appendResidentAttr(rec, off, 0x10, 0, body = standardInfoBody(nowFileTime, 0x06))
    off =
      appendResidentAttr(
        rec,
        off,
        0x30,
        1,
        body = fileNameBody(5L, 5, "\$Secure", nowFileTime, 0L, 0L, 0x06),
      )
    off = appendResidentAttr(rec, off, 0x80, 2, nameUtf16 = "\$SDS", body = ByteArray(0))
    return finalizeMftRecord(rec, off, nextAttrId = 3)
  }

  private fun buildExtendRecord(nowFileTime: Long): ByteArray {
    val rec = initMftHeader(11, seqNum = 1, flags = 0x0003)
    var off = 0x38
    off = appendResidentAttr(rec, off, 0x10, 0, body = standardInfoBody(nowFileTime, 0x06))
    off =
      appendResidentAttr(
        rec,
        off,
        0x30,
        1,
        body = fileNameBody(5L, 5, "\$Extend", nowFileTime, 0L, 0L, 0x10000006),
      )
    off = appendResidentAttr(rec, off, 0x90, 2, nameUtf16 = "\$I30", body = buildEmptyIndexRootBody(4096))
    return finalizeMftRecord(rec, off, nextAttrId = 3)
  }

  private fun buildRootDirRecord(
    nowFileTime: Long,
    indexAllocCluster: Long,
    indexAllocClusters: Long,
    clusterBytes: Int,
  ): ByteArray {
    val rec = initMftHeader(5, seqNum = 5, flags = 0x0003)
    var off = 0x38
    off = appendResidentAttr(rec, off, 0x10, 0, body = standardInfoBody(nowFileTime, 0x16))
    off =
      appendResidentAttr(
        rec,
        off,
        0x30,
        1,
        body = fileNameBody(5L, 5, ".", nowFileTime, 0L, 0L, 0x10000006),
      )
    // Large $INDEX_ROOT pointing to VCN 0 in $INDEX_ALLOCATION
    val indexRoot = buildParentIndexRootBody(clusterBytes)
    off = appendResidentAttr(rec, off, 0x90, 2, nameUtf16 = "\$I30", body = indexRoot)
    off =
      appendNonResidentAttr(
        rec,
        off,
        0xA0,
        3,
        nameUtf16 = "\$I30",
        runs = listOf(indexAllocCluster to indexAllocClusters),
        allocatedSize = indexAllocClusters * clusterBytes,
        dataSize = INDEX_BLOCK_SIZE.toLong(),
      )
    off = appendResidentAttr(rec, off, 0xB0, 4, nameUtf16 = "\$I30", body = byteArrayOf(0x01))
    return finalizeMftRecord(rec, off, nextAttrId = 5)
  }

  private fun buildEmptyIndexRootBody(clusterBytes: Int): ByteArray {
    val body = ByteArray(48)
    LittleEndian.putU32(body, 0, 0x30L) // Indexed attr type = $FILE_NAME
    LittleEndian.putU32(body, 4, 1L) // Collation rule = FILENAME
    LittleEndian.putU32(body, 8, INDEX_BLOCK_SIZE.toLong())
    body[12] = if (clusterBytes >= INDEX_BLOCK_SIZE) 1 else (-12).toByte()
    // Index Header starts at offset 16
    LittleEndian.putU32(body, 16, 16L) // Entries offset relative to header
    LittleEndian.putU32(body, 20, 32L) // Total size of index entries
    LittleEndian.putU32(body, 24, 32L) // Allocated size
    body[28] = 0x00 // Small index (no $INDEX_ALLOCATION)
    // End-of-index entry at offset 32 (16 bytes)
    LittleEndian.putU16(body, 32 + 8, 16)
    LittleEndian.putU16(body, 32 + 12, 0x02) // INDEX_ENTRY_END
    return body
  }

  private fun buildParentIndexRootBody(clusterBytes: Int): ByteArray {
    val body = ByteArray(56)
    LittleEndian.putU32(body, 0, 0x30L)
    LittleEndian.putU32(body, 4, 1L)
    LittleEndian.putU32(body, 8, INDEX_BLOCK_SIZE.toLong())
    body[12] = if (clusterBytes >= INDEX_BLOCK_SIZE) 1 else (-12).toByte()
    // Index Header at offset 16
    LittleEndian.putU32(body, 16, 16L)
    LittleEndian.putU32(body, 20, 40L) // Entries total size
    LittleEndian.putU32(body, 24, 40L)
    body[28] = 0x01 // Has sub-nodes ($INDEX_ALLOCATION)
    // Single end entry pointing to child VCN 0 at offset 32 (24 bytes)
    LittleEndian.putU16(body, 32 + 8, 24) // Entry length
    LittleEndian.putU16(body, 32 + 12, 0x03) // INDEX_ENTRY_NODE | INDEX_ENTRY_END
    LittleEndian.putU64(body, 32 + 16, 0L) // Child VCN 0
    return body
  }

  private data class IndexFileMeta(
    val mftRecordNum: Long,
    val seqNum: Int,
    val name: String,
    val nowFileTime: Long,
    val dataSize: Long,
    val allocatedSize: Long,
    val flags: Int,
  )

  private fun buildRootIndxBlock(entries: List<IndexFileMeta>): ByteArray {
    val block = ByteArray(INDEX_BLOCK_SIZE)
    LittleEndian.asciiBytes("INDX", 4).copyInto(block, 0x00)
    val usaOffset = 0x28
    val usaCount = 1 + (INDEX_BLOCK_SIZE / NTFS_USA_STRIDE) // 9
    LittleEndian.putU16(block, 0x04, usaOffset)
    LittleEndian.putU16(block, 0x06, usaCount)
    LittleEndian.putU64(block, 0x10, 0L) // This block VCN = 0

    // Index header starts at 0x18; entries start after USA padded to 8-byte boundary
    val headerStart = 0x18
    val entriesStart = align8(usaOffset + usaCount * 2) // 0x40 (64)
    val entriesRelOffset = entriesStart - headerStart // 40

    var cursor = entriesStart
    for (item in entries) {
      val fnBody =
        fileNameBody(
          parentRecord = 5L,
          parentSeq = 5,
          name = item.name,
          nowFileTime = item.nowFileTime,
          dataSize = item.dataSize,
          allocatedSize = item.allocatedSize,
          fileFlags = item.flags,
        )
      val entryLen = align8(16 + fnBody.size)
      val mftRef = (item.seqNum.toLong() shl 48) or (item.mftRecordNum and 0x0000FFFFFFFFFFFFL)
      LittleEndian.putU64(block, cursor, mftRef)
      LittleEndian.putU16(block, cursor + 8, entryLen)
      LittleEndian.putU16(block, cursor + 10, fnBody.size)
      LittleEndian.putU16(block, cursor + 12, 0) // Leaf entry
      fnBody.copyInto(block, cursor + 16)
      cursor += entryLen
    }

    // End entry (16 bytes)
    LittleEndian.putU16(block, cursor + 8, 16)
    LittleEndian.putU16(block, cursor + 12, 0x02) // INDEX_ENTRY_END
    cursor += 16

    val totalEntriesSize = cursor - headerStart
    LittleEndian.putU32(block, headerStart + 0, entriesRelOffset.toLong())
    LittleEndian.putU32(block, headerStart + 4, totalEntriesSize.toLong())
    LittleEndian.putU32(block, headerStart + 8, (INDEX_BLOCK_SIZE - headerStart).toLong())
    block[headerStart + 12] = 0x00 // Leaf INDX block

    applyUsaFixup(block, usaOffset = usaOffset, usaCount = usaCount)
    return block
  }

  private fun compareNtfsNames(a: String, b: String): Int {
    val ua = a.uppercase()
    val ub = b.uppercase()
    val minLen = minOf(ua.length, ub.length)
    for (i in 0 until minLen) {
      val diff = ua[i].code - ub[i].code
      if (diff != 0) return diff
    }
    return ua.length - ub.length
  }

  private fun buildUpCaseTable(): ByteArray {
    val table = ByteArray(65536 * 2)
    for (cp in 0 until 65536) {
      val up = cp.toChar().uppercaseChar().code
      LittleEndian.putU16(table, cp * 2, up)
    }
    return table
  }

  private data class AttrDefEntry(
    val name: String,
    val type: Int,
    val flags: Int,
    val minSize: Long,
    val maxSize: Long,
  )

  private fun buildAttrDefTable(): ByteArray {
    val defs =
      listOf(
        AttrDefEntry("\$STANDARD_INFORMATION", 0x10, 0x40, 48, 72),
        AttrDefEntry("\$ATTRIBUTE_LIST", 0x20, 0x80, 0, -1),
        AttrDefEntry("\$FILE_NAME", 0x30, 0x42, 68, 578),
        AttrDefEntry("\$OBJECT_ID", 0x40, 0x40, 0, 256),
        AttrDefEntry("\$SECURITY_DESCRIPTOR", 0x50, 0x80, 0, -1),
        AttrDefEntry("\$VOLUME_NAME", 0x60, 0x40, 2, 256),
        AttrDefEntry("\$VOLUME_INFORMATION", 0x70, 0x40, 12, 12),
        AttrDefEntry("\$DATA", 0x80, 0x00, 0, -1),
        AttrDefEntry("\$INDEX_ROOT", 0x90, 0x40, 0, -1),
        AttrDefEntry("\$INDEX_ALLOCATION", 0xA0, 0x80, 0, -1),
        AttrDefEntry("\$BITMAP", 0xB0, 0x80, 0, -1),
        AttrDefEntry("\$REPARSE_POINT", 0xC0, 0x80, 0, 16384),
        AttrDefEntry("\$EA_INFORMATION", 0xD0, 0x40, 8, 8),
        AttrDefEntry("\$EA", 0xE0, 0x00, 0, 65536),
        AttrDefEntry("\$LOGGED_UTILITY_STREAM", 0x100, 0x80, 0, 65536),
      )
    val table = ByteArray(16 * 160)
    defs.forEachIndexed { idx, entry ->
      val base = idx * 160
      val nameBytes = LittleEndian.utf16LeBytes(entry.name)
      nameBytes.copyInto(table, base, 0, minOf(nameBytes.size, 128))
      LittleEndian.putU32(table, base + 128, entry.type.toLong())
      LittleEndian.putU32(table, base + 132, 0L)
      LittleEndian.putU32(table, base + 136, 1L) // Collation
      LittleEndian.putU32(table, base + 140, entry.flags.toLong())
      LittleEndian.putU64(table, base + 144, entry.minSize)
      LittleEndian.putU64(table, base + 152, entry.maxSize)
    }
    return table
  }

  internal fun encodeDataRuns(runs: List<Pair<Long, Long>>): ByteArray {
    val out = ArrayList<Byte>(runs.size * 10 + 1)
    var prevLcn = 0L
    for ((lcn, length) in runs) {
      val delta = lcn - prevLcn
      prevLcn = lcn

      val lenBytes = encodeUnsignedMinimal(length)
      val offBytes = encodeSignedMinimal(delta)
      val header = ((offBytes.size and 0x0F) shl 4) or (lenBytes.size and 0x0F)
      out.add(header.toByte())
      lenBytes.forEach { out.add(it) }
      offBytes.forEach { out.add(it) }
    }
    out.add(0x00)
    return out.toByteArray()
  }

  private fun encodeUnsignedMinimal(value: Long): ByteArray {
    if (value == 0L) return byteArrayOf(0x00)
    val buf = ArrayList<Byte>(8)
    var v = value
    while (v != 0L) {
      buf.add((v and 0xFFL).toByte())
      v = v ushr 8
    }
    // Keep positive sign bit clear per NTFS mapping-pair spec
    if ((buf.last().toInt() and 0x80) != 0) {
      buf.add(0x00)
    }
    return buf.toByteArray()
  }

  private fun encodeSignedMinimal(value: Long): ByteArray {
    val buf = ArrayList<Byte>(8)
    var v = value
    while (true) {
      val b = (v and 0xFFL).toByte()
      buf.add(b)
      v = v shr 8
      val isNegativeByte = (b.toInt() and 0x80) != 0
      if ((v == 0L && !isNegativeByte) || (v == -1L && isNegativeByte)) {
        break
      }
    }
    return buf.toByteArray()
  }

  private fun align8(v: Int): Int = (v + 7) and 7.inv()
}
