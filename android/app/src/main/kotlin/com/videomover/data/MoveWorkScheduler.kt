package com.videomover.data

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.UUID

object MoveWorkScheduler {
    fun enqueueBatch(context: Context, batchId: String): UUID {
        val request = OneTimeWorkRequestBuilder<MovePipelineWorker>()
            .setInputData(Data.Builder().putString(MovePipelineWorker.KEY_BATCH_ID, batchId).build())
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "video-mover-$batchId",
            ExistingWorkPolicy.KEEP,
            request,
        )
        return request.id
    }

    fun cancel(context: Context, workId: UUID) = WorkManager.getInstance(context).cancelWorkById(workId)

    const val TAG = "video-mover-pipeline"
}
