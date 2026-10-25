package com.videomover.data

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.videomover.conversion.SourceVideoInfo
import com.videomover.conversion.outputVideoSize
import com.videomover.machine.FileState
import com.videomover.model.MoveFileRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class ConversionRun(
    val record: MoveFileRecord,
    val outputFile: File?,
    val outputSize: String? = null,
)

/** Phase 5 conversion. It never accepts a phone source URI and never deletes the USB original. */
@OptIn(UnstableApi::class)
class Media3ConversionRepository(
    private val context: Context,
    private val records: MoveRecordStore,
) {
    private val conversionMutex = Mutex()

    suspend fun convert(recordId: String): ConversionRun = conversionMutex.withLock {
        withContext(Dispatchers.IO) {
            val record = records.find(recordId) ?: error("Move record $recordId was not found.")
            require(record.state == FileState.SOURCE_DELETED.name) {
                "Conversion is allowed only after SOURCE_DELETED; current state is ${record.state}."
            }
            require(record.sourceDeletedAtEpochMs != null) {
                "Conversion is blocked because source deletion has not been confirmed."
            }
            val inputUri = requireNotNull(record.destinationFileUri) {
                "Conversion is blocked because the verified USB file URI is missing."
            }
            val started = record.copy(
                state = FileState.CONVERTING.name,
                errorMessage = null,
                attentionReason = null,
                updatedAtEpochMs = System.currentTimeMillis(),
            )
            records.upsert(started)

            val output = File.createTempFile("video-mover-${record.id}-", ".mp4", context.cacheDir)
            try {
                val metadata = readMetadata(inputUri)
                val size = outputVideoSize(metadata, record.outputResolution)
                export(inputUri, output, size.scale, size.width)
                validateOutput(output, size.width, size.height)
                val completed = started.copy(
                    state = FileState.CONVERTED.name,
                    outputUri = output.toURI().toString(),
                    errorMessage = null,
                    updatedAtEpochMs = System.currentTimeMillis(),
                )
                records.upsert(completed)
                ConversionRun(completed, output, "${size.width}x${size.height}")
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                output.delete()
                val failed = started.copy(
                    state = FileState.FAILED.name,
                    outputUri = null,
                    errorMessage = "Conversion failed; the verified USB original was kept. ${error.message ?: error.javaClass.simpleName}",
                    updatedAtEpochMs = System.currentTimeMillis(),
                )
                records.upsert(failed)
                ConversionRun(failed, null)
            }
        }
    }

    private fun readMetadata(uri: String): SourceVideoInfo {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, android.net.Uri.parse(uri))
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            SourceVideoInfo(requireNotNull(width), requireNotNull(height), rotation)
        } finally {
            retriever.release()
        }
    }

    private suspend fun export(inputUri: String, output: File, scale: Float, outputWidth: Int) {
        val effects = if (scale < 0.9999f) {
            Effects(
                emptyList(),
                listOf(ScaleAndRotateTransformation.Builder().setScale(scale, scale).build()),
            )
        } else {
            null
        }
        val edited = EditedMediaItem.Builder(MediaItem.fromUri(inputUri))
            .apply { if (effects != null) setEffects(effects) }
            .build()
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                val transformer = Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .setEncoderFactory(
                        DefaultEncoderFactory.Builder(context)
                            .setRequestedVideoEncoderSettings(
                                VideoEncoderSettings.Builder()
                                    .setBitrate(if (outputWidth >= 1280) 4_000_000 else 2_500_000)
                                    .setiFrameIntervalSeconds(2f)
                                    .build(),
                            )
                            .build(),
                    )
                    .setPortraitEncodingEnabled(true)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: androidx.media3.transformer.Composition, result: ExportResult) {
                            if (continuation.isActive) continuation.resume(Unit)
                        }

                        override fun onError(
                            composition: androidx.media3.transformer.Composition,
                            result: ExportResult,
                            exception: ExportException,
                        ) {
                            if (continuation.isActive) continuation.resumeWithException(exception)
                        }
                    })
                    .build()
                continuation.invokeOnCancellation { transformer.cancel() }
                transformer.start(edited, output.absolutePath)
            }
        }
    }

    private fun validateOutput(file: File, expectedWidth: Int, expectedHeight: Int) {
        require(file.isFile && file.length() > 0) { "The conversion produced no output file." }
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            var videoFound = false
            var videoDurationUs = 0L
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime == MimeTypes.VIDEO_H264) {
                    videoFound = true
                    videoDurationUs = maxOf(videoDurationUs, format.getLong(MediaFormat.KEY_DURATION))
                    val width = format.getInteger(MediaFormat.KEY_WIDTH)
                    val height = format.getInteger(MediaFormat.KEY_HEIGHT)
                    require((width == expectedWidth && height == expectedHeight) ||
                        (width == expectedHeight && height == expectedWidth)) {
                        "Unexpected output dimensions ${width}x$height."
                    }
                } else if (mime.startsWith("audio/")) {
                    require(mime == MimeTypes.AUDIO_AAC) { "The output audio track is not AAC." }
                }
            }
            require(videoFound) { "The output has no H.264 video track." }
            require(videoDurationUs > 0L) { "The output video duration is empty." }
        } finally {
            extractor.release()
        }
    }
}
