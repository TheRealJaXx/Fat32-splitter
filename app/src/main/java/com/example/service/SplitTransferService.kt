package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.MainActivity
import com.example.R
import com.example.engine.SafFileHelper
import com.example.engine.SplitNaming
import com.example.engine.StreamSplitter
import com.example.format.ActiveFormatJobConfig
import com.example.format.DriveFormatCoordinator
import com.example.format.FormatJobState
import com.example.format.UsbScsiBlockDevice
import com.example.model.QueueItemStatus
import com.example.model.SmallFileAction
import com.example.model.TransferProgressState
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class SplitTransferService : Service() {

  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var activeJob: Job? = null

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    createNotificationChannel()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_CANCEL -> {
        TransferCoordinator.requestCancel()
        activeJob?.cancel()
        stopForegroundAndSelf()
        return START_NOT_STICKY
      }
      ACTION_START_SPLIT -> {
        val config = TransferCoordinator.pendingSplitJob
        if (config == null) {
          stopSelf(startId)
          return START_NOT_STICKY
        }
        TransferCoordinator.cancelRequested = false
        startForegroundWithNotification(
          title = "Splitting files for FAT32",
          detail = "Preparing ${config.items.size} item(s)...",
          progressPercent = 0,
          indeterminate = true,
        )
        launchSplitJob(config)
      }
      ACTION_START_JOIN -> {
        val config = TransferCoordinator.pendingJoinJob
        if (config == null) {
          stopSelf(startId)
          return START_NOT_STICKY
        }
        TransferCoordinator.cancelRequested = false
        startForegroundWithNotification(
          title = "Merging split parts",
          detail = "Preparing ${config.groups.size} file(s)...",
          progressPercent = 0,
          indeterminate = true,
        )
        launchJoinJob(config)
      }
      ACTION_START_FORMAT -> {
        val config = DriveFormatCoordinator.pendingFormatJob
        if (config == null) {
          stopSelf(startId)
          return START_NOT_STICKY
        }
        startForegroundWithNotification(
          title = "Formatting ${config.deviceTitle}",
          detail = "Writing ${config.filesystem.title} structures...",
          progressPercent = 0,
          indeterminate = false,
        )
        launchFormatJob(config)
      }
    }
    return START_NOT_STICKY
  }

  private fun launchSplitJob(config: ActiveSplitJobConfig) {
    activeJob?.cancel()
    activeJob =
      serviceScope.launch {
        val startMs = SystemClock.elapsedRealtime()
        var completedFiles = 0
        var skippedFiles = 0
        var partsCreated = 0
        var bytesDoneOverall = 0L

        val actionableBytes =
          config.items.sumOf { item ->
            if (item.needsSplit(config.chunkSizeBytes) || config.smallFileAction == SmallFileAction.COPY) {
              item.sizeBytes
            } else {
              0L
            }
          }

        try {
          val resolver = contentResolver
          config.items.forEachIndexed { index, item ->
            if (TransferCoordinator.cancelRequested) throw CancellationException("Cancelled")

            val shouldSplit = item.needsSplit(config.chunkSizeBytes)
            if (!shouldSplit && config.smallFileAction == SmallFileAction.SKIP) {
              skippedFiles++
              TransferCoordinator.updateSplitItem(item.id) {
                it.copy(status = QueueItemStatus.SKIPPED)
              }
              return@forEachIndexed
            }

            TransferCoordinator.updateSplitItem(item.id) {
              it.copy(
                status = QueueItemStatus.PROCESSING,
                totalParts =
                  if (shouldSplit) {
                    SplitNaming.calculatePartCount(item.sizeBytes, config.chunkSizeBytes)
                  } else {
                    1
                  },
              )
            }

            val targetParentUri =
              SafFileHelper.ensureSubdirectories(resolver, config.outputTreeUri, item.relativePath)

            if (shouldSplit) {
              val result =
                StreamSplitter.splitStream(
                  sourceName = item.displayName,
                  sourceSizeBytes = item.sizeBytes,
                  chunkSizeBytes = config.chunkSizeBytes,
                  namingFormat = config.namingFormat,
                  computeChecksum = config.verifyChecksums,
                  openInput = {
                    resolver.openInputStream(item.uri)
                      ?: throw IOException("Cannot read ${item.displayName}")
                  },
                  openPartOutput = { _, partFileName ->
                    val outUri =
                      SafFileHelper.createOrReplaceDocument(
                        resolver = resolver,
                        treeUri = config.outputTreeUri,
                        parentDocUri = targetParentUri,
                        fileName = partFileName,
                        isSplitPart = true,
                        overwriteExisting = config.overwriteExisting,
                      )
                    resolver.openOutputStream(outUri, "w")
                      ?: resolver.openOutputStream(outUri)
                      ?: throw IOException("Cannot write $partFileName")
                  },
                  onProgress = { event ->
                    val currentOverall = bytesDoneOverall + event.bytesTransferredInFile
                    val elapsed = (SystemClock.elapsedRealtime() - startMs).coerceAtLeast(1L)
                    val speed = (currentOverall * 1000L) / elapsed
                    val pct =
                      if (actionableBytes > 0L) {
                        ((currentOverall * 100L) / actionableBytes).toInt().coerceIn(0, 100)
                      } else {
                        0
                      }
                    TransferCoordinator.updateSplitItem(item.id) {
                      it.copy(
                        bytesProcessed = event.bytesTransferredInFile,
                        currentPartIndex = event.currentPartIndex,
                        totalParts = event.totalParts,
                      )
                    }
                    TransferCoordinator.setProgress(
                      TransferProgressState.Running(
                        isJoinMode = false,
                        currentFileName = item.displayName,
                        currentPartLabel =
                          "Part ${event.currentPartIndex + 1}/${event.totalParts}: ${event.currentPartName}",
                        fileIndex = index + 1,
                        totalFiles = config.items.size,
                        bytesProcessed = currentOverall,
                        totalBytes = actionableBytes,
                        speedBytesPerSec = speed,
                        elapsedMillis = elapsed,
                      )
                    )
                    updateProgressNotification(
                      title = "Splitting ${item.displayName}",
                      detail =
                        "Part ${event.currentPartIndex + 1}/${event.totalParts} • ${SplitNaming.formatSpeed(speed)}",
                      progressPercent = pct,
                    )
                  },
                )

              if (!result.byteExact || !result.checksumMatched) {
                throw IOException("Verification failed for ${item.displayName}")
              }

              bytesDoneOverall += result.totalBytesWritten
              partsCreated += result.parts.size
              completedFiles++
              TransferCoordinator.updateSplitItem(item.id) {
                it.copy(
                  status = QueueItemStatus.COMPLETED,
                  bytesProcessed = result.totalBytesWritten,
                  totalParts = result.parts.size,
                  crc32Hex = result.outputCrc32Hex,
                )
              }
            } else {
              // Mirror smaller file intact
              val copied =
                StreamSplitter.copyStream(
                  fileName = item.displayName,
                  expectedBytes = item.sizeBytes,
                  openInput = {
                    resolver.openInputStream(item.uri)
                      ?: throw IOException("Cannot read ${item.displayName}")
                  },
                  openOutput = {
                    val outUri =
                      SafFileHelper.createOrReplaceDocument(
                        resolver = resolver,
                        treeUri = config.outputTreeUri,
                        parentDocUri = targetParentUri,
                        fileName = item.displayName,
                        isSplitPart = false,
                        overwriteExisting = config.overwriteExisting,
                      )
                    resolver.openOutputStream(outUri, "w")
                      ?: resolver.openOutputStream(outUri)
                      ?: throw IOException("Cannot write ${item.displayName}")
                  },
                  onProgress = { copiedInFile ->
                    val currentOverall = bytesDoneOverall + copiedInFile
                    val elapsed = (SystemClock.elapsedRealtime() - startMs).coerceAtLeast(1L)
                    val speed = (currentOverall * 1000L) / elapsed
                    TransferCoordinator.updateSplitItem(item.id) {
                      it.copy(bytesProcessed = copiedInFile)
                    }
                    TransferCoordinator.setProgress(
                      TransferProgressState.Running(
                        isJoinMode = false,
                        currentFileName = item.displayName,
                        currentPartLabel = "Mirroring FAT32-safe file",
                        fileIndex = index + 1,
                        totalFiles = config.items.size,
                        bytesProcessed = currentOverall,
                        totalBytes = actionableBytes,
                        speedBytesPerSec = speed,
                        elapsedMillis = elapsed,
                      )
                    )
                  },
                )
              bytesDoneOverall += copied
              partsCreated++
              completedFiles++
              TransferCoordinator.updateSplitItem(item.id) {
                it.copy(
                  status = QueueItemStatus.COMPLETED,
                  bytesProcessed = copied,
                  totalParts = 1,
                )
              }
            }
          }

          val totalElapsed = (SystemClock.elapsedRealtime() - startMs).coerceAtLeast(1L)
          TransferCoordinator.setProgress(
            TransferProgressState.Completed(
              isJoinMode = false,
              filesProcessed = completedFiles,
              filesSkipped = skippedFiles,
              partsCreated = partsCreated,
              totalBytesProcessed = bytesDoneOverall,
              elapsedMillis = totalElapsed,
              verifiedChecksums = config.verifyChecksums,
            )
          )
          postCompletionNotification(
            title = "Split complete",
            detail = "$completedFiles file(s) • $partsCreated output file(s) ready for FAT32",
          )
        } catch (_: CancellationException) {
          TransferCoordinator.setProgress(
            TransferProgressState.Cancelled(
              isJoinMode = false,
              filesCompletedBeforeCancel = completedFiles,
            )
          )
        } catch (t: Throwable) {
          TransferCoordinator.setProgress(
            TransferProgressState.Failed(
              isJoinMode = false,
              message = t.message ?: "Unexpected error while splitting files",
            )
          )
        } finally {
          TransferCoordinator.pendingSplitJob = null
          stopForegroundAndSelf()
        }
      }
  }

  private fun launchJoinJob(config: ActiveJoinJobConfig) {
    activeJob?.cancel()
    activeJob =
      serviceScope.launch {
        val startMs = SystemClock.elapsedRealtime()
        var completedGroups = 0
        var bytesDoneOverall = 0L
        val totalBytesAll = config.groups.sumOf { it.totalBytes }

        try {
          val resolver = contentResolver
          val rootOutDocUri =
            SafFileHelper.ensureSubdirectories(resolver, config.outputTreeUri, "merged")

          config.groups.forEachIndexed { index, group ->
            if (TransferCoordinator.cancelRequested) throw CancellationException("Cancelled")
            if (!group.isContiguous) {
              throw IOException("Cannot merge ${group.baseFileName}: missing parts ${group.missingIndices}")
            }

            TransferCoordinator.updateJoinGroup(group.id) {
              it.copy(status = QueueItemStatus.PROCESSING)
            }

            val result =
              StreamSplitter.joinStreams(
                outputFileName = group.baseFileName,
                totalExpectedBytes = group.totalBytes,
                partNames = group.parts.map { it.displayName },
                computeChecksum = config.verifyChecksums,
                openPartInput = { idx ->
                  val partUri = group.parts[idx].uri
                  resolver.openInputStream(partUri)
                    ?: throw IOException("Cannot read part ${group.parts[idx].displayName}")
                },
                openMergedOutput = {
                  val outUri =
                    SafFileHelper.createOrReplaceDocument(
                      resolver = resolver,
                      treeUri = config.outputTreeUri,
                      parentDocUri = rootOutDocUri,
                      fileName = group.baseFileName,
                      isSplitPart = false,
                      overwriteExisting = config.overwriteExisting,
                    )
                  resolver.openOutputStream(outUri, "w")
                    ?: resolver.openOutputStream(outUri)
                    ?: throw IOException("Cannot write ${group.baseFileName}")
                },
                onProgress = { event ->
                  val currentOverall = bytesDoneOverall + event.bytesTransferredInFile
                  val elapsed = (SystemClock.elapsedRealtime() - startMs).coerceAtLeast(1L)
                  val speed = (currentOverall * 1000L) / elapsed
                  val pct =
                    if (totalBytesAll > 0L) {
                      ((currentOverall * 100L) / totalBytesAll).toInt().coerceIn(0, 100)
                    } else {
                      0
                    }
                  TransferCoordinator.updateJoinGroup(group.id) {
                    it.copy(bytesProcessed = event.bytesTransferredInFile)
                  }
                  TransferCoordinator.setProgress(
                    TransferProgressState.Running(
                      isJoinMode = true,
                      currentFileName = group.baseFileName,
                      currentPartLabel =
                        "Reading part ${event.currentPartIndex + 1}/${event.totalParts}: ${event.currentPartName}",
                      fileIndex = index + 1,
                      totalFiles = config.groups.size,
                      bytesProcessed = currentOverall,
                      totalBytes = totalBytesAll,
                      speedBytesPerSec = speed,
                      elapsedMillis = elapsed,
                    )
                  )
                  updateProgressNotification(
                    title = "Merging ${group.baseFileName}",
                    detail =
                      "Part ${event.currentPartIndex + 1}/${event.totalParts} • ${SplitNaming.formatSpeed(speed)}",
                    progressPercent = pct,
                  )
                },
              )

            bytesDoneOverall += result.totalBytesWritten
            completedGroups++
            TransferCoordinator.updateJoinGroup(group.id) {
              it.copy(
                status = QueueItemStatus.COMPLETED,
                bytesProcessed = result.totalBytesWritten,
                crc32Hex = result.outputCrc32Hex,
              )
            }
          }

          val totalElapsed = (SystemClock.elapsedRealtime() - startMs).coerceAtLeast(1L)
          TransferCoordinator.setProgress(
            TransferProgressState.Completed(
              isJoinMode = true,
              filesProcessed = completedGroups,
              filesSkipped = 0,
              partsCreated = completedGroups,
              totalBytesProcessed = bytesDoneOverall,
              elapsedMillis = totalElapsed,
              verifiedChecksums = config.verifyChecksums,
            )
          )
          postCompletionNotification(
            title = "Merge complete",
            detail = "Restored $completedGroups file(s) (${SplitNaming.formatBytesHuman(bytesDoneOverall)})",
          )
        } catch (_: CancellationException) {
          TransferCoordinator.setProgress(
            TransferProgressState.Cancelled(
              isJoinMode = true,
              filesCompletedBeforeCancel = completedGroups,
            )
          )
        } catch (t: Throwable) {
          TransferCoordinator.setProgress(
            TransferProgressState.Failed(
              isJoinMode = true,
              message = t.message ?: "Unexpected error while merging files",
            )
          )
        } finally {
          TransferCoordinator.pendingJoinJob = null
          stopForegroundAndSelf()
        }
      }
  }

  private fun launchFormatJob(config: ActiveFormatJobConfig) {
    activeJob?.cancel()
    activeJob =
      serviceScope.launch {
        try {
          DriveFormatCoordinator.setState(
            FormatJobState.Formatting(
              deviceTitle = config.deviceTitle,
              filesystem = config.filesystem,
              stageLabel = "Claiming USB Mass Storage interface...",
              progress = 0.02f,
            )
          )

          UsbScsiBlockDevice.open(applicationContext, config.usbDevice).use { blockDev ->
            val completed =
              DriveFormatCoordinator.formatBlockDevice(
                device = blockDev,
                deviceTitle = config.deviceTitle,
                filesystem = config.filesystem,
                volumeLabel = config.volumeLabel,
                clusterSizeBytes = config.clusterSizeBytes,
                onProgress = { stage, pct ->
                  DriveFormatCoordinator.setState(
                    FormatJobState.Formatting(
                      deviceTitle = config.deviceTitle,
                      filesystem = config.filesystem,
                      stageLabel = stage,
                      progress = pct,
                    )
                  )
                  updateProgressNotification(
                    title = "Formatting ${config.deviceTitle}",
                    detail = stage,
                    progressPercent = (pct * 100f).toInt().coerceIn(0, 100),
                  )
                },
              )
            DriveFormatCoordinator.setState(completed)
            postCompletionNotification(
              title = "Drive formatted (${config.filesystem.title})",
              detail = "${config.deviceTitle} is ready. Unplug and replug to mount.",
            )
          }
        } catch (t: Throwable) {
          DriveFormatCoordinator.setState(
            FormatJobState.Failed(
              t.message ?: "USB formatting failed. Check OTG connection and retry."
            )
          )
        } finally {
          DriveFormatCoordinator.pendingFormatJob = null
          stopForegroundAndSelf()
        }
      }
  }

  private fun startForegroundWithNotification(
    title: String,
    detail: String,
    progressPercent: Int,
    indeterminate: Boolean,
  ) {
    val notification = buildProgressNotification(title, detail, progressPercent, indeterminate)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      ServiceCompat.startForeground(
        this,
        NOTIFICATION_ID,
        notification,
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
      )
    } else {
      startForeground(NOTIFICATION_ID, notification)
    }
  }

  private fun updateProgressNotification(title: String, detail: String, progressPercent: Int) {
    val mgr = getSystemService(NotificationManager::class.java)
    mgr.notify(
      NOTIFICATION_ID,
      buildProgressNotification(title, detail, progressPercent, indeterminate = false),
    )
  }

  private fun postCompletionNotification(title: String, detail: String) {
    val openIntent =
      PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )
    val notification =
      NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_download_done)
        .setContentTitle(title)
        .setContentText(detail)
        .setAutoCancel(true)
        .setContentIntent(openIntent)
        .build()
    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID + 1, notification)
  }

  private fun buildProgressNotification(
    title: String,
    detail: String,
    progressPercent: Int,
    indeterminate: Boolean,
  ): Notification {
    val openIntent =
      PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )
    val cancelIntent =
      PendingIntent.getService(
        this,
        1,
        Intent(this, SplitTransferService::class.java).apply { action = ACTION_CANCEL },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(android.R.drawable.stat_sys_download)
      .setContentTitle(title)
      .setContentText(detail)
      .setOnlyAlertOnce(true)
      .setOngoing(true)
      .setProgress(100, progressPercent, indeterminate)
      .setContentIntent(openIntent)
      .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.action_cancel), cancelIntent)
      .build()
  }

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel =
        NotificationChannel(
          CHANNEL_ID,
          getString(R.string.notification_channel_name),
          NotificationManager.IMPORTANCE_LOW,
        ).apply {
          description = getString(R.string.notification_channel_desc)
        }
      getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
  }

  private fun stopForegroundAndSelf() {
    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    stopSelf()
  }

  override fun onDestroy() {
    super.onDestroy()
    serviceScope.cancel()
  }

  companion object {
    private const val CHANNEL_ID = "split666_transfer_channel"
    private const val NOTIFICATION_ID = 6661
    const val ACTION_START_SPLIT = "com.example.action.START_SPLIT"
    const val ACTION_START_JOIN = "com.example.action.START_JOIN"
    const val ACTION_START_FORMAT = "com.example.action.START_FORMAT"
    const val ACTION_CANCEL = "com.example.action.CANCEL_TRANSFER"

    fun startSplit(context: Context, config: ActiveSplitJobConfig) {
      TransferCoordinator.pendingSplitJob = config
      val intent = Intent(context, SplitTransferService::class.java).apply { action = ACTION_START_SPLIT }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
      } else {
        context.startService(intent)
      }
    }

    fun startJoin(context: Context, config: ActiveJoinJobConfig) {
      TransferCoordinator.pendingJoinJob = config
      val intent = Intent(context, SplitTransferService::class.java).apply { action = ACTION_START_JOIN }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
      } else {
        context.startService(intent)
      }
    }

    fun startFormat(context: Context, config: ActiveFormatJobConfig) {
      DriveFormatCoordinator.pendingFormatJob = config
      val intent = Intent(context, SplitTransferService::class.java).apply { action = ACTION_START_FORMAT }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
      } else {
        context.startService(intent)
      }
    }

    fun cancel(context: Context) {
      TransferCoordinator.requestCancel()
      val intent = Intent(context, SplitTransferService::class.java).apply { action = ACTION_CANCEL }
      context.startService(intent)
    }
  }
}
