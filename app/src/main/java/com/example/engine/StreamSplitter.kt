package com.example.engine

import com.example.model.SplitNamingFormat
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.CRC32
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

data class SplitPartRecord(
  val partIndex: Int,
  val partFileName: String,
  val bytesWritten: Long,
)

data class SplitExecutionResult(
  val originalFileName: String,
  val totalBytesRead: Long,
  val totalBytesWritten: Long,
  val parts: List<SplitPartRecord>,
  val sourceCrc32Hex: String?,
  val outputCrc32Hex: String?,
  val byteExact: Boolean,
  val checksumMatched: Boolean,
)

data class JoinExecutionResult(
  val outputFileName: String,
  val totalBytesWritten: Long,
  val partsMerged: Int,
  val outputCrc32Hex: String?,
)

data class StreamProgressEvent(
  val bytesTransferredInFile: Long,
  val totalFileBytes: Long,
  val currentPartIndex: Int,
  val totalParts: Int,
  val currentPartName: String,
)

/**
 * Pure stream-based binary splitter and merger with bounded memory (4 MiB buffer),
 * cooperative coroutine cancellation, byte-exact accounting, and live CRC32 verification.
 */
object StreamSplitter {

  const val DEFAULT_BUFFER_SIZE = 4 * 1024 * 1024 // 4 MiB

  suspend fun splitStream(
    sourceName: String,
    sourceSizeBytes: Long,
    chunkSizeBytes: Long,
    namingFormat: SplitNamingFormat,
    computeChecksum: Boolean,
    bufferSize: Int = DEFAULT_BUFFER_SIZE,
    openInput: suspend () -> InputStream,
    openPartOutput: suspend (partIndex: Int, partFileName: String) -> OutputStream,
    onProgress: suspend (StreamProgressEvent) -> Unit,
  ): SplitExecutionResult {
    val safeChunkSize = SplitNaming.clampFat32ChunkSize(chunkSizeBytes)
    val expectedParts = SplitNaming.calculatePartCount(sourceSizeBytes, safeChunkSize)
    val buffer = ByteArray(bufferSize.coerceAtLeast(8192))

    val sourceCrc = if (computeChecksum) CRC32() else null
    val outputCrc = if (computeChecksum) CRC32() else null

    val partRecords = mutableListOf<SplitPartRecord>()
    var totalRead = 0L
    var totalWritten = 0L
    var lastProgressReportBytes = 0L

    openInput().use { input ->
      var partIndex = 0
      var reachedEof = false

      while (!reachedEof) {
        coroutineContext.ensureActive()

        val partName = SplitNaming.partFileName(sourceName, partIndex, namingFormat)
        var bytesInCurrentPart = 0L
        var partStreamOpened = false

        var currentOut: OutputStream? = null
        try {
          while (bytesInCurrentPart < safeChunkSize) {
            coroutineContext.ensureActive()

            val remainingInChunk = safeChunkSize - bytesInCurrentPart
            val maxToRead = minOf(buffer.size.toLong(), remainingInChunk).toInt()
            val read = input.read(buffer, 0, maxToRead)
            if (read == -1) {
              reachedEof = true
              break
            }

            if (currentOut == null) {
              currentOut = openPartOutput(partIndex, partName)
              partStreamOpened = true
              onProgress(
                StreamProgressEvent(
                  bytesTransferredInFile = totalWritten,
                  totalFileBytes = sourceSizeBytes,
                  currentPartIndex = partIndex,
                  totalParts = expectedParts,
                  currentPartName = partName,
                )
              )
            }

            currentOut.write(buffer, 0, read)
            sourceCrc?.update(buffer, 0, read)
            outputCrc?.update(buffer, 0, read)

            totalRead += read
            totalWritten += read
            bytesInCurrentPart += read

            if (totalWritten - lastProgressReportBytes >= 1024L * 1024L || reachedEof) {
              lastProgressReportBytes = totalWritten
              onProgress(
                StreamProgressEvent(
                  bytesTransferredInFile = totalWritten,
                  totalFileBytes = sourceSizeBytes,
                  currentPartIndex = partIndex,
                  totalParts = expectedParts,
                  currentPartName = partName,
                )
              )
            }
          }

          // Handle 0-byte source file cleanly by creating .66600 / .001 empty part
          if (partIndex == 0 && !partStreamOpened && sourceSizeBytes == 0L) {
            currentOut = openPartOutput(0, partName)
            partStreamOpened = true
          }

          if (partStreamOpened) {
            currentOut?.flush()
            partRecords.add(
              SplitPartRecord(
                partIndex = partIndex,
                partFileName = partName,
                bytesWritten = bytesInCurrentPart,
              )
            )
            partIndex++
          }
        } finally {
          currentOut?.close()
        }
      }
    }

    onProgress(
      StreamProgressEvent(
        bytesTransferredInFile = totalWritten,
        totalFileBytes = maxOf(sourceSizeBytes, totalWritten),
        currentPartIndex = (partRecords.size - 1).coerceAtLeast(0),
        totalParts = partRecords.size.coerceAtLeast(1),
        currentPartName = partRecords.lastOrNull()?.partFileName ?: sourceName,
      )
    )

    val srcHex = sourceCrc?.value?.let { String.format("%08X", it) }
    val outHex = outputCrc?.value?.let { String.format("%08X", it) }
    val byteExact = totalRead == totalWritten && (sourceSizeBytes <= 0L || totalWritten == sourceSizeBytes)

    return SplitExecutionResult(
      originalFileName = sourceName,
      totalBytesRead = totalRead,
      totalBytesWritten = totalWritten,
      parts = partRecords,
      sourceCrc32Hex = srcHex,
      outputCrc32Hex = outHex,
      byteExact = byteExact,
      checksumMatched = !computeChecksum || (srcHex != null && srcHex == outHex),
    )
  }

  suspend fun joinStreams(
    outputFileName: String,
    totalExpectedBytes: Long,
    partNames: List<String>,
    computeChecksum: Boolean,
    bufferSize: Int = DEFAULT_BUFFER_SIZE,
    openPartInput: suspend (indexInList: Int) -> InputStream,
    openMergedOutput: suspend () -> OutputStream,
    onProgress: suspend (StreamProgressEvent) -> Unit,
  ): JoinExecutionResult {
    val buffer = ByteArray(bufferSize.coerceAtLeast(8192))
    val crc = if (computeChecksum) CRC32() else null
    var totalWritten = 0L
    var lastReportedBytes = 0L

    openMergedOutput().use { output ->
      partNames.forEachIndexed { idx, partName ->
        coroutineContext.ensureActive()
        onProgress(
          StreamProgressEvent(
            bytesTransferredInFile = totalWritten,
            totalFileBytes = totalExpectedBytes,
            currentPartIndex = idx,
            totalParts = partNames.size,
            currentPartName = partName,
          )
        )

        openPartInput(idx).use { input ->
          while (true) {
            coroutineContext.ensureActive()
            val read = input.read(buffer)
            if (read == -1) break
            output.write(buffer, 0, read)
            crc?.update(buffer, 0, read)
            totalWritten += read

            if (totalWritten - lastReportedBytes >= 1024L * 1024L) {
              lastReportedBytes = totalWritten
              onProgress(
                StreamProgressEvent(
                  bytesTransferredInFile = totalWritten,
                  totalFileBytes = totalExpectedBytes,
                  currentPartIndex = idx,
                  totalParts = partNames.size,
                  currentPartName = partName,
                )
              )
            }
          }
        }
      }
      output.flush()
    }

    onProgress(
      StreamProgressEvent(
        bytesTransferredInFile = totalWritten,
        totalFileBytes = maxOf(totalExpectedBytes, totalWritten),
        currentPartIndex = (partNames.size - 1).coerceAtLeast(0),
        totalParts = partNames.size.coerceAtLeast(1),
        currentPartName = outputFileName,
      )
    )

    return JoinExecutionResult(
      outputFileName = outputFileName,
      totalBytesWritten = totalWritten,
      partsMerged = partNames.size,
      outputCrc32Hex = crc?.value?.let { String.format("%08X", it) },
    )
  }

  suspend fun copyStream(
    fileName: String,
    expectedBytes: Long,
    bufferSize: Int = DEFAULT_BUFFER_SIZE,
    openInput: suspend () -> InputStream,
    openOutput: suspend () -> OutputStream,
    onProgress: suspend (Long) -> Unit,
  ): Long {
    val buffer = ByteArray(bufferSize.coerceAtLeast(8192))
    var totalCopied = 0L
    var lastReported = 0L
    openInput().use { input ->
      openOutput().use { output ->
        while (true) {
          coroutineContext.ensureActive()
          val read = input.read(buffer)
          if (read == -1) break
          output.write(buffer, 0, read)
          totalCopied += read
          if (totalCopied - lastReported >= 1024L * 1024L) {
            lastReported = totalCopied
            onProgress(totalCopied)
          }
        }
        output.flush()
      }
    }
    onProgress(totalCopied)
    return totalCopied
  }
}
