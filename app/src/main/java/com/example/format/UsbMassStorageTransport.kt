package com.example.format

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Communicates with USB Mass Storage (Class 0x08, Subclass 0x06 SCSI, Protocol 0x50 BOT)
 * devices via Android UsbManager using Bulk-Only Transport (CBW/CSW) without root.
 */
class UsbScsiBlockDevice private constructor(
  private val connection: UsbDeviceConnection,
  private val usbInterface: UsbInterface,
  private val bulkIn: UsbEndpoint,
  private val bulkOut: UsbEndpoint,
  override val sectorSizeBytes: Int,
  override val totalSectors: Long,
  val scsiVendor: String,
  val scsiProduct: String,
) : BlockDevice {

  private var tagCounter = 1
  private val maxSectorsPerTransfer = 128 // 64 KiB for 512-byte sectors

  override suspend fun readSectors(startLba: Long, sectorCount: Int): ByteArray =
    withContext(Dispatchers.IO) {
      if (sectorCount <= 0) return@withContext ByteArray(0)
      val totalBytes = sectorCount * sectorSizeBytes
      val out = ByteArray(totalBytes)
      var remaining = sectorCount
      var currentLba = startLba
      var outOffset = 0

      while (remaining > 0) {
        val batch = minOf(remaining, maxSectorsPerTransfer)
        val batchBytes = batch * sectorSizeBytes
        val cdb =
          if (currentLba + batch > 0xFFFFFFFFL) {
            buildRead16Cdb(currentLba, batch)
          } else {
            buildRead10Cdb(currentLba, batch)
          }
        val buf = executeCommand(cdb, dataOut = null, expectedInLength = batchBytes)
        buf.copyInto(out, outOffset, 0, minOf(buf.size, batchBytes))
        remaining -= batch
        currentLba += batch
        outOffset += batchBytes
      }
      out
    }

  override suspend fun writeSectors(startLba: Long, data: ByteArray) =
    withContext(Dispatchers.IO) {
      require(data.size % sectorSizeBytes == 0) {
        "Write payload size (${data.size}) must be a multiple of sectorSize ($sectorSizeBytes)"
      }
      val totalSectorsToWrite = data.size / sectorSizeBytes
      var remaining = totalSectorsToWrite
      var currentLba = startLba
      var dataOffset = 0

      while (remaining > 0) {
        val batch = minOf(remaining, maxSectorsPerTransfer)
        val batchBytes = batch * sectorSizeBytes
        val slice = data.copyOfRange(dataOffset, dataOffset + batchBytes)
        val cdb =
          if (currentLba + batch > 0xFFFFFFFFL) {
            buildWrite16Cdb(currentLba, batch)
          } else {
            buildWrite10Cdb(currentLba, batch)
          }
        executeCommand(cdb, dataOut = slice, expectedInLength = 0)
        remaining -= batch
        currentLba += batch
        dataOffset += batchBytes
      }
    }

  override suspend fun syncCache() =
    withContext(Dispatchers.IO) {
      val cdb = ByteArray(10)
      cdb[0] = 0x35 // SYNCHRONIZE CACHE (10)
      runCatching { executeCommand(cdb, dataOut = null, expectedInLength = 0) }
      Unit
    }

  override fun close() {
    runCatching { connection.releaseInterface(usbInterface) }
    runCatching { connection.close() }
  }

  private fun executeCommand(
    cdb: ByteArray,
    dataOut: ByteArray?,
    expectedInLength: Int,
  ): ByteArray {
    val tag = tagCounter++
    val isIn = dataOut == null && expectedInLength > 0
    val dataLen = dataOut?.size ?: expectedInLength
    val cbw = ByteArray(31)
    LittleEndian.putU32(cbw, 0, CBW_SIGNATURE)
    LittleEndian.putU32(cbw, 4, tag.toLong())
    LittleEndian.putU32(cbw, 8, dataLen.toLong())
    cbw[12] = if (isIn) 0x80.toByte() else 0x00
    cbw[13] = 0x00 // LUN 0
    cbw[14] = cdb.size.toByte()
    cdb.copyInto(cbw, 15, 0, minOf(cdb.size, 16))

    val cbwSent = connection.bulkTransfer(bulkOut, cbw, cbw.size, USB_TIMEOUT_MS)
    if (cbwSent != cbw.size) {
      throw IOException("USB CBW transfer failed (sent=$cbwSent)")
    }

    var resultData = ByteArray(0)
    if (isIn && expectedInLength > 0) {
      resultData = ByteArray(expectedInLength)
      var offset = 0
      while (offset < expectedInLength) {
        val chunk = minOf(MAX_BULK_BYTES, expectedInLength - offset)
        val tmp = ByteArray(chunk)
        val read = connection.bulkTransfer(bulkIn, tmp, chunk, USB_TIMEOUT_MS)
        if (read < 0) {
          clearHaltRecovery()
          throw IOException("USB bulk IN failed ($read)")
        }
        tmp.copyInto(resultData, offset, 0, read)
        offset += read
        if (read < chunk) break
      }
    } else if (dataOut != null && dataOut.isNotEmpty()) {
      var offset = 0
      while (offset < dataOut.size) {
        val chunk = minOf(MAX_BULK_BYTES, dataOut.size - offset)
        val tmp = dataOut.copyOfRange(offset, offset + chunk)
        val written = connection.bulkTransfer(bulkOut, tmp, tmp.size, USB_TIMEOUT_MS)
        if (written < 0) {
          clearHaltRecovery()
          throw IOException("USB bulk OUT failed ($written)")
        }
        offset += written
      }
    }

    val csw = ByteArray(13)
    val cswRead = connection.bulkTransfer(bulkIn, csw, csw.size, USB_TIMEOUT_MS)
    if (cswRead != 13) {
      clearHaltRecovery()
      throw IOException("USB CSW read failed ($cswRead)")
    }
    val sig = LittleEndian.getU32(csw, 0)
    if (sig != CSW_SIGNATURE) {
      throw IOException("Invalid USB CSW signature: 0x${sig.toString(16)}")
    }
    val status = csw[12].toInt() and 0xFF
    if (status != 0) {
      val sense = if (cdb[0].toInt() != 0x03) requestSenseSummary() else ""
      throw IOException("SCSI command 0x${(cdb[0].toInt() and 0xFF).toString(16)} failed (status=$status $sense)")
    }
    return resultData
  }

  private fun requestSenseSummary(): String =
    runCatching {
      val cdb = byteArrayOf(0x03, 0, 0, 0, 18, 0)
      val sense = executeCommand(cdb, dataOut = null, expectedInLength = 18)
      val key = sense[2].toInt() and 0x0F
      val asc = sense[12].toInt() and 0xFF
      val ascq = sense[13].toInt() and 0xFF
      "senseKey=0x${key.toString(16)} asc=0x${asc.toString(16)} ascq=0x${ascq.toString(16)}"
    }.getOrDefault("")

  private fun clearHaltRecovery() {
    // Bulk-Only Mass Storage Reset (0xFF)
    connection.controlTransfer(0x21, 0xFF, 0, usbInterface.id, null, 0, 2000)
  }

  private fun buildRead10Cdb(lba: Long, blocks: Int): ByteArray =
    byteArrayOf(
      0x28,
      0,
      ((lba ushr 24) and 0xFF).toByte(),
      ((lba ushr 16) and 0xFF).toByte(),
      ((lba ushr 8) and 0xFF).toByte(),
      (lba and 0xFF).toByte(),
      0,
      ((blocks ushr 8) and 0xFF).toByte(),
      (blocks and 0xFF).toByte(),
      0,
    )

  private fun buildWrite10Cdb(lba: Long, blocks: Int): ByteArray =
    byteArrayOf(
      0x2A,
      0,
      ((lba ushr 24) and 0xFF).toByte(),
      ((lba ushr 16) and 0xFF).toByte(),
      ((lba ushr 8) and 0xFF).toByte(),
      (lba and 0xFF).toByte(),
      0,
      ((blocks ushr 8) and 0xFF).toByte(),
      (blocks and 0xFF).toByte(),
      0,
    )

  private fun buildRead16Cdb(lba: Long, blocks: Int): ByteArray {
    val cdb = ByteArray(16)
    cdb[0] = 0x88.toByte()
    for (i in 0 until 8) {
      cdb[2 + i] = ((lba ushr ((7 - i) * 8)) and 0xFF).toByte()
    }
    cdb[10] = ((blocks ushr 24) and 0xFF).toByte()
    cdb[11] = ((blocks ushr 16) and 0xFF).toByte()
    cdb[12] = ((blocks ushr 8) and 0xFF).toByte()
    cdb[13] = (blocks and 0xFF).toByte()
    return cdb
  }

  private fun buildWrite16Cdb(lba: Long, blocks: Int): ByteArray {
    val cdb = ByteArray(16)
    cdb[0] = 0x8A.toByte()
    for (i in 0 until 8) {
      cdb[2 + i] = ((lba ushr ((7 - i) * 8)) and 0xFF).toByte()
    }
    cdb[10] = ((blocks ushr 24) and 0xFF).toByte()
    cdb[11] = ((blocks ushr 16) and 0xFF).toByte()
    cdb[12] = ((blocks ushr 8) and 0xFF).toByte()
    cdb[13] = (blocks and 0xFF).toByte()
    return cdb
  }

  companion object {
    private const val CBW_SIGNATURE = 0x43425355L
    private const val CSW_SIGNATURE = 0x53425355L
    private const val MAX_BULK_BYTES = 16 * 1024
    private const val USB_TIMEOUT_MS = 10_000

    fun findMassStorageInterface(device: UsbDevice): Triple<UsbInterface, UsbEndpoint, UsbEndpoint>? {
      for (i in 0 until device.interfaceCount) {
        val intf = device.getInterface(i)
        if (
          intf.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE &&
            intf.interfaceSubclass == 0x06 &&
            intf.interfaceProtocol == 0x50
        ) {
          var inEp: UsbEndpoint? = null
          var outEp: UsbEndpoint? = null
          for (e in 0 until intf.endpointCount) {
            val ep = intf.getEndpoint(e)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
              if (ep.direction == UsbConstants.USB_DIR_IN) inEp = ep
              else if (ep.direction == UsbConstants.USB_DIR_OUT) outEp = ep
            }
          }
          if (inEp != null && outEp != null) {
            return Triple(intf, inEp, outEp)
          }
        }
      }
      return null
    }

    fun listAttachedMassStorageDevices(context: Context): List<UsbDriveInfo> {
      val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return emptyList()
      return usbManager.deviceList.values
        .filter { findMassStorageInterface(it) != null }
        .map { dev ->
          UsbDriveInfo(
            deviceId = dev.deviceId,
            deviceName = dev.deviceName,
            vendorName = dev.manufacturerName.orEmpty().trim(),
            productName = dev.productName.orEmpty().trim(),
            vendorId = dev.vendorId,
            productId = dev.productId,
            hasPermission = usbManager.hasPermission(dev),
          )
        }
        .sortedBy { it.deviceId }
    }

    suspend fun open(context: Context, device: UsbDevice): UsbScsiBlockDevice =
      withContext(Dispatchers.IO) {
        val usbManager =
          context.getSystemService(Context.USB_SERVICE) as? UsbManager
            ?: throw IOException("USB Host service unavailable on this device")

        val (intf, inEp, outEp) =
          findMassStorageInterface(device)
            ?: throw IOException("Device is not a SCSI Bulk-Only Mass Storage drive")

        val connection =
          usbManager.openDevice(device)
            ?: throw IOException("Unable to open USB connection (permission missing or device disconnected)")

        if (!connection.claimInterface(intf, true)) {
          connection.close()
          throw IOException("Could not claim USB Mass Storage interface")
        }

        val tempDev =
          UsbScsiBlockDevice(
            connection = connection,
            usbInterface = intf,
            bulkIn = inEp,
            bulkOut = outEp,
            sectorSizeBytes = 512,
            totalSectors = 0L,
            scsiVendor = "",
            scsiProduct = "",
          )

        try {
          // Wait through initial Unit Attention on newly attached USB sticks
          var ready = false
          repeat(4) {
            val ok = runCatching {
              tempDev.executeCommand(ByteArray(6), dataOut = null, expectedInLength = 0)
            }.isSuccess
            if (ok) {
              ready = true
              return@repeat
            }
            delay(150)
          }
          if (!ready) {
            // Try one final TEST UNIT READY to surface sense error if genuinely not ready
            tempDev.executeCommand(ByteArray(6), dataOut = null, expectedInLength = 0)
          }

          val inquiry =
            tempDev.executeCommand(
              cdb = byteArrayOf(0x12, 0, 0, 0, 36, 0),
              dataOut = null,
              expectedInLength = 36,
            )
          val vendor = String(inquiry, 8, 8, Charsets.US_ASCII).trim()
          val product = String(inquiry, 16, 16, Charsets.US_ASCII).trim()

          val cap10 =
            tempDev.executeCommand(
              cdb = ByteArray(10).apply { this[0] = 0x25 },
              dataOut = null,
              expectedInLength = 8,
            )
          val lastLba32 = readBeU32(cap10, 0)
          var blockSize = readBeU32(cap10, 4).toInt()
          var totalSectors = if (lastLba32 == 0xFFFFFFFFL) -1L else lastLba32 + 1L

          if (totalSectors <= 0L) {
            val cap16Cdb = ByteArray(16).apply {
              this[0] = 0x9E.toByte()
              this[1] = 0x10
              this[13] = 32
            }
            val cap16 = tempDev.executeCommand(cap16Cdb, dataOut = null, expectedInLength = 32)
            val lastLba64 = readBeU64(cap16, 0)
            blockSize = readBeU32(cap16, 8).toInt()
            totalSectors = lastLba64 + 1L
          }

          UsbScsiBlockDevice(
            connection = connection,
            usbInterface = intf,
            bulkIn = inEp,
            bulkOut = outEp,
            sectorSizeBytes = blockSize.coerceAtLeast(512),
            totalSectors = totalSectors.coerceAtLeast(0L),
            scsiVendor = vendor,
            scsiProduct = product,
          )
        } catch (t: Throwable) {
          tempDev.close()
          throw t
        }
      }

    private fun readBeU32(buf: ByteArray, offset: Int): Long =
      ((buf[offset].toLong() and 0xFFL) shl 24) or
        ((buf[offset + 1].toLong() and 0xFFL) shl 16) or
        ((buf[offset + 2].toLong() and 0xFFL) shl 8) or
        (buf[offset + 3].toLong() and 0xFFL)

    private fun readBeU64(buf: ByteArray, offset: Int): Long {
      var v = 0L
      for (i in 0 until 8) {
        v = (v shl 8) or (buf[offset + i].toLong() and 0xFFL)
      }
      return v
    }
  }
}
