package com.videomover.data

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.videomover.library.VideoItem
import com.videomover.model.OutputResolution
import java.util.UUID

object MoveWorkScheduler {
    fun enqueue(
        context: Context,
        batchId: String,
        item: VideoItem,
        destinationTreeUri: String,
        resolution: OutputResolution,
    ): UUID {
        val input = Data.Builder()
            .putString(MovePipelineWorker.KEY_BATCH_ID, batchId)
            .putString(MovePipelineWorker.KEY_URI, item.uri)
            .putString(MovePipelineWorker.KEY_NAME, item.fileName)
            .putString(MovePipelineWorker.KEY_PARENT_URI, item.parentDirectoryUri)
            .putString(MovePipelineWorker.KEY_RELATIVE_PATH, item.relativePath)
            .putString(MovePipelineWorker.KEY_SOURCE_DATA_PATH, item.sourceDataPath)
            .putString(MovePipelineWorker.KEY_DESTINATION_TREE, destinationTreeUri)
            .putString(MovePipelineWorker.KEY_RESOLUTION, resolution.label)
            .build()
        val request = OneTimeWorkRequestBuilder<MovePipelineWorker>()
            .setInputData(input)
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context).beginUniqueWork(
            "video-mover-$batchId",
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        ).enqueue()
        return request.id
    }

    fun cancel(context: Context, workId: UUID) {
        WorkManager.getInstance(context).cancelWorkById(workId)
    }

    const val TAG = "video-mover-pipeline"
}
