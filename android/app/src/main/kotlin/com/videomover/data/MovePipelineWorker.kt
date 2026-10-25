package com.videomover.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.videomover.MainActivity
import com.videomover.library.VideoItem
import com.videomover.machine.FileState
import com.videomover.transfer.CollisionAction
import com.videomover.transfer.CollisionResolution
import com.videomover.transfer.TransferResult

/** One durable batch worker. A per-file problem is recorded and never stops later files. */
class MovePipelineWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo("Preparing video move", 0)

    override suspend fun doWork(): Result {
        val batchId = inputData.getString(KEY_BATCH_ID) ?: return Result.failure()
        val records = MoveRecordStore(applicationContext)
        val transfers = SafTransferRepository(applicationContext, records)
        val conversions = Media3ConversionRepository(applicationContext, records)
        val outputs = OriginalOutputRepository(applicationContext, records)
        val jobs = records.forBatch(batchId)
        if (jobs.isEmpty()) return Result.success()
        setForeground(foregroundInfo("Moving videos", 0))

        jobs.forEachIndexed { index, record ->
            if (isStopped) return Result.success()
            val progress = ((index * 100) / jobs.size).coerceIn(0, 99)
            setProgress(Data.Builder().putInt(KEY_PROGRESS, progress).putString(KEY_STATUS, record.originalFileName).build())
            when (record.state) {
                FileState.PENDING.name -> {
                    val run = transfers.moveOne(
                        batchId = batchId,
                        item = record.toVideoItem(),
                        destinationTreeUri = record.externalDestinationTreeUri,
                        resolution = record.outputResolution,
                        collision = CollisionResolution(CollisionAction.ASK),
                        requestedName = record.destinationFileName ?: record.originalFileName,
                        existingRecord = record,
                        isCancelled = { isStopped },
                    )
                    if (run.result is TransferResult.Completed) finishConversion(run.record, conversions, outputs)
                }
                FileState.SOURCE_DELETED.name -> finishConversion(record, conversions, outputs)
            }
        }
        setProgress(Data.Builder().putInt(KEY_PROGRESS, 100).putString(KEY_STATUS, "Finished").build())
        return Result.success()
    }

    private suspend fun finishConversion(
        record: com.videomover.model.MoveFileRecord,
        conversions: Media3ConversionRepository,
        outputs: OriginalOutputRepository,
    ) {
        val converted = conversions.convert(record.id)
        val file = converted.outputFile ?: return
        outputs.place(converted.record.id, file, CollisionResolution(CollisionAction.ASK))
    }

    private fun com.videomover.model.MoveFileRecord.toVideoItem() = VideoItem(
        id = sourceUri, uri = sourceUri, fileName = originalFileName, sizeBytes = null,
        durationMs = null, width = null, height = null, dateAddedEpochSec = null,
        relativePath = sourceRelativePath, mimeType = null, accessible = true, metadataWarning = null,
        parentDirectoryUri = sourceParentDirectoryUri, sourceDataPath = sourceDataPath,
    )

    private fun foregroundInfo(status: String, progress: Int): ForegroundInfo {
        ensureChannel()
        val cancel = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val open = PendingIntent.getActivity(applicationContext, 7402, Intent(applicationContext, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Video Mover").setContentText(status).setContentIntent(open)
            .setProgress(100, progress, progress == 0).setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancel).build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(NOTIFICATION_ID, notification)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) applicationContext.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL_ID, "Video moves", NotificationManager.IMPORTANCE_LOW))
    }

    companion object {
        const val KEY_BATCH_ID = "batch_id"
        const val KEY_PROGRESS = "progress"
        const val KEY_STATUS = "status"
        private const val CHANNEL_ID = "video-mover-progress"
        private const val NOTIFICATION_ID = 7401
    }
}
