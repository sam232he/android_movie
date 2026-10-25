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
import com.videomover.library.VideoItem
import com.videomover.model.OutputResolution
import com.videomover.transfer.CollisionAction
import com.videomover.transfer.CollisionResolution
import com.videomover.machine.FileState
import java.io.File
import com.videomover.MainActivity

/** Durable one-file pipeline. The WorkManager unique name prevents duplicate queued runs. */
class MovePipelineWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo("Preparing video move…", 0)

    override suspend fun doWork(): Result {
        setForeground(foregroundInfo("Moving and verifying video…", 0))
        val item = inputItem() ?: return failed("The queued video metadata is incomplete.")
        val destination = inputData.getString(KEY_DESTINATION_TREE) ?: return failed("The USB destination is missing.")
        val batchId = inputData.getString(KEY_BATCH_ID) ?: id.toString()
        val resolution = OutputResolution.entries.firstOrNull { it.label == inputData.getString(KEY_RESOLUTION) }
            ?: OutputResolution.P480
        val records = MoveRecordStore(applicationContext)
        val transfers = SafTransferRepository(applicationContext, records)
        val conversions = Media3ConversionRepository(applicationContext, records)
        val outputs = OriginalOutputRepository(applicationContext, records)
        val existing = records.forBatch(batchId).firstOrNull { it.sourceUri == item.uri }
        if (existing != null) {
            return when (existing.state) {
                FileState.SOURCE_DELETED.name,
                FileState.CONVERTING.name,
                FileState.CONVERTED.name,
                -> finishAfterDeletion(existing, conversions, outputs)
                FileState.PENDING.name -> failed("The queued file is still pending; it will be retried safely.")
                else -> failed(existing.errorMessage ?: "This file needs manual recovery before it can continue.")
            }
        }
        val run = transfers.moveOne(
            batchId = batchId,
            item = item,
            destinationTreeUri = destination,
            resolution = resolution,
            collision = CollisionResolution(CollisionAction.ASK),
            isCancelled = { isStopped },
        )
        if (isStopped) return Result.failure(errorData("Cancelled before destructive completion."))
        when (val transfer = run.result) {
            is com.videomover.transfer.TransferResult.Completed -> {
                return finishAfterDeletion(run.record, conversions, outputs)
            }
            is com.videomover.transfer.TransferResult.NeedsAttention -> {
                notifyFinished("Video move needs attention", transfer.message)
                return Result.failure(errorData(transfer.message))
            }
            is com.videomover.transfer.TransferResult.Collision -> return failed("Destination name conflict: ${transfer.requestedName}. Open the app to choose an action.")
            com.videomover.transfer.TransferResult.Cancelled,
            com.videomover.transfer.TransferResult.Skipped,
            is com.videomover.transfer.TransferResult.Verified,
            -> return failed("The video move did not finish.")
        }
    }

    private suspend fun finishAfterDeletion(
        record: com.videomover.model.MoveFileRecord,
        conversions: Media3ConversionRepository,
        outputs: OriginalOutputRepository,
    ): Result {
        val sourceDeletedRecord = if (record.state == FileState.CONVERTING.name) {
            record.copy(state = FileState.SOURCE_DELETED.name, errorMessage = "Conversion was interrupted and is restarting safely.")
                .also { MoveRecordStore(applicationContext).upsert(it) }
        } else record
        setProgress(Data.Builder().putInt(KEY_PROGRESS, 50).putString(KEY_STATUS, "Source deleted; converting").build())
        setForeground(foregroundInfo("Converting verified USB copy…", 50))
        val conversion = if (sourceDeletedRecord.state == FileState.CONVERTED.name && sourceDeletedRecord.outputUri?.startsWith("file:") == true) {
            val cached = android.net.Uri.parse(sourceDeletedRecord.outputUri).path?.let(::File)
            if (cached?.isFile == true) ConversionRun(sourceDeletedRecord, cached) else conversions.convert(sourceDeletedRecord.id)
        } else {
            conversions.convert(sourceDeletedRecord.id)
        }
        val file = conversion.outputFile ?: return failed(conversion.record.errorMessage ?: "Conversion failed; USB original was kept.")
        return when (val placement = outputs.place(conversion.record.id, file, CollisionResolution(CollisionAction.ASK))) {
            is OutputPlacementResult.Placed -> {
                notifyFinished("Video move complete", "Converted output saved beside the original.")
                Result.success(Data.Builder().putInt(KEY_PROGRESS, 100).build())
            }
            is OutputPlacementResult.Collision -> failed("Output name conflict: ${placement.fileName}. Open the app to choose Rename, Skip, or Cancel.")
            OutputPlacementResult.Skipped -> failed("Output skipped; USB original remains untouched.")
            OutputPlacementResult.Cancelled -> failed("Output cancelled; USB original remains untouched.")
            is OutputPlacementResult.NeedsAttention -> failed(placement.message)
        }
    }

    private fun inputItem(): VideoItem? {
        val uri = inputData.getString(KEY_URI) ?: return null
        val name = inputData.getString(KEY_NAME) ?: return null
        return VideoItem(
            id = uri,
            uri = uri,
            fileName = name,
            sizeBytes = null,
            durationMs = null,
            width = null,
            height = null,
            dateAddedEpochSec = null,
            relativePath = inputData.getString(KEY_RELATIVE_PATH),
            mimeType = null,
            accessible = true,
            metadataWarning = null,
            parentDirectoryUri = inputData.getString(KEY_PARENT_URI),
            sourceDataPath = inputData.getString(KEY_SOURCE_DATA_PATH),
        )
    }

    private fun failed(message: String): Result = Result.failure(errorData(message))

    private fun errorData(message: String): Data = Data.Builder().putString(KEY_ERROR, message).build()

    private fun foregroundInfo(status: String, progress: Int): ForegroundInfo {
        ensureNotificationChannel()
        val cancel = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val openApp = PendingIntent.getActivity(
            applicationContext,
            7402,
            Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Video Mover")
            .setContentText(status)
            .setContentIntent(openApp)
            .setProgress(100, progress, progress == 0)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancel)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun notifyFinished(title: String, message: String) {
        ensureNotificationChannel()
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID + 1, NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(title)
            .setContentText(message)
            .setContentIntent(PendingIntent.getActivity(
                applicationContext,
                7402,
                Intent(applicationContext, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ))
            .setAutoCancel(true)
            .build())
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            applicationContext.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Video moves", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    companion object {
        const val KEY_BATCH_ID = "batch_id"
        const val KEY_URI = "source_uri"
        const val KEY_NAME = "source_name"
        const val KEY_PARENT_URI = "source_parent_uri"
        const val KEY_RELATIVE_PATH = "source_relative_path"
        const val KEY_SOURCE_DATA_PATH = "source_data_path"
        const val KEY_DESTINATION_TREE = "destination_tree"
        const val KEY_RESOLUTION = "resolution"
        const val KEY_PROGRESS = "progress"
        const val KEY_STATUS = "status"
        const val KEY_ERROR = "error"
        private const val CHANNEL_ID = "video-mover-progress"
        private const val NOTIFICATION_ID = 7401
    }
}
