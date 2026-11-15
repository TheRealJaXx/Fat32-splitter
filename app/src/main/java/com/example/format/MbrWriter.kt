package com.example.format

import kotlin.random.Random

data class MbrPartitionLayout(
  val partitionStartLba: Long,
  val partitionSectors: Long,
  val mbrTypeByte: Int,
  val diskSignature: Long,
)

object MbrWriter {

  private const val PARTITION_TABLE_OFFSET = 0x1BE
  private const val MBR_MAX_SECTORS = 0xFFFFFFFFL

  fun inspectPartitionScheme(sector0: ByteArray, sector1: ByteArray?): PartitionScheme {
    if (sector1 != null && sector1.size >= 8) {
      val efiSig = String(sector1, 0, 8, Charsets.US_ASCII)
      if (efiSig == "EFI PART") return PartitionScheme.GPT
    }

    if (sector0.size < 512) return PartitionScheme.UNKNOWN
    val sig0 = sector0[510].toInt() and 0xFF
    val sig1 = sector0[511].toInt() and 0xFF
    if (sig0 != 0x55 || sig1 != 0xAA) return PartitionScheme.RAW_OR_EMPTY

    var hasPartition = false
    for (slot in 0 until 4) {
      val entryOffset = PARTITION_TABLE_OFFSET + slot * 16
      val type = sector0[entryOffset + 4].toInt() and 0xFF
      val sectorCount = LittleEndian.getU32(sector0, entryOffset + 12)
      if (type == 0xEE && sectorCount > 0L) return PartitionScheme.GPT
      if (type != 0x00 && sectorCount > 0L) hasPartition = true
    }
    return if (hasPartition) PartitionScheme.MBR else PartitionScheme.RAW_OR_EMPTY
  }

  fun computeLayout(
    totalSectors: Long,
    sectorSizeBytes: Int,
    filesystem: TargetFilesystem,
  ): MbrPartitionLayout {
    require(sectorSizeBytes == 512 || sectorSizeBytes == 4096) {
      "Unsupported physical sector size: $sectorSizeBytes bytes"
    }
    val alignSectors = maxOf(2048L, (1_048_576L / sectorSizeBytes).toLong())
    require(totalSectors > alignSectors + 4096L) {
      "Drive capacity too small for MBR + ${filesystem.title}"
    }

    // Reserve at least 34 trailing sectors so backup GPT cleanup never overlaps the partition
    val usableTail = totalSectors - 34L
    val rawAvailable = (usableTail - alignSectors).coerceAtLeast(1L)
    val clampedSectors = minOf(rawAvailable, MBR_MAX_SECTORS)

    return MbrPartitionLayout(
      partitionStartLba = alignSectors,
      partitionSectors = clampedSectors,
      mbrTypeByte = filesystem.mbrTypeByte,
      diskSignature = Random.nextLong(1L, 0xFFFFFFFEL),
    )
  }

  fun buildMbrSector(layout: MbrPartitionLayout, sectorSizeBytes: Int): ByteArray {
    val sector = ByteArray(sectorSizeBytes)

    // Disk signature at 0x1B8
    LittleEndian.putU32(sector, 0x1B8, layout.diskSignature)

    // First MBR partition entry at 0x1BE
    val p0 = PARTITION_TABLE_OFFSET
    sector[p0] = 0x00 // Non-bootable data partition
    sector[p0 + 1] = 0xFE.toByte()
    sector[p0 + 2] = 0xFF.toByte()
    sector[p0 + 3] = 0xFF.toByte()
    sector[p0 + 4] = (layout.mbrTypeByte and 0xFF).toByte()
    sector[p0 + 5] = 0xFE.toByte()
    sector[p0 + 6] = 0xFF.toByte()
    sector[p0 + 7] = 0xFF.toByte()
    LittleEndian.putU32(sector, p0 + 8, layout.partitionStartLba)
    LittleEndian.putU32(sector, p0 + 12, layout.partitionSectors)

    // Boot signature 0x55AA at 0x1FE
    sector[510] = 0x55
    sector[511] = 0xAA.toByte()
    return sector
  }

  suspend fun writeCleanMbr(
    device: BlockDevice,
    layout: MbrPartitionLayout,
    onProgress: suspend (String, Float) -> Unit,
  ) {
    val sectorSize = device.sectorSizeBytes
    onProgress("Clearing GPT headers & pre-partition gap...", 0.05f)

    // Clear LBA 1..min(64, partitionStartLba - 1) to wipe primary GPT header/entries
    val clearCount = minOf(64L, layout.partitionStartLba - 1L).toInt()
    if (clearCount > 0) {
      device.writeSectors(1L, ByteArray(clearCount * sectorSize))
    }

    // Clear trailing 34 sectors to wipe any backup GPT header/partition array
    val tailClearCount = minOf(34L, device.totalSectors - (layout.partitionStartLba + layout.partitionSectors)).toInt()
    if (tailClearCount > 0) {
      val tailStart = device.totalSectors - tailClearCount
      device.writeSectors(tailStart, ByteArray(tailClearCount * sectorSize))
    }

    onProgress("Writing MBR partition table (Sector 0)...", 0.15f)
    val mbrSector = buildMbrSector(layout, sectorSize)
    device.writeSectors(0L, mbrSector)
  }
}
