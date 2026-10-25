package com.videomover.transfer

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class VerifiedTransferTest {
    @Test
    fun verifiedMoveDeletesSourceOnlyAfterMatchingReadBack() {
        val destination = FakeDestination()
        val source = FakeSource("content://phone/1", "clip.mov", "hello".toByteArray())

        val result = VerifiedTransfer(destination, tempName = { ".part" }).move(source)

        assertIs<TransferResult.Completed>(result)
        assertEquals(listOf(SourceDeleteResult.DELETED), source.deleteCalls)
        assertEquals("hello", destination.files["clip.mov"]?.decodeToString())
        assertTrue(destination.deletedTemps.isEmpty())
    }

    @Test
    fun checksumMismatchKeepsSourceAndDeletesOnlyTemp() {
        val destination = FakeDestination(corruptReadBack = true)
        val source = FakeSource("content://phone/1", "clip.mov", "hello".toByteArray())

        val result = VerifiedTransfer(destination, tempName = { ".part" }).move(source)

        assertEquals(Attention.CHECKSUM_MISMATCH, assertIs<TransferResult.NeedsAttention>(result).reason)
        assertTrue(source.deleteCalls.isEmpty())
        assertTrue(destination.files.isEmpty())
        assertEquals(listOf(".part"), destination.deletedTemps)
    }

    @Test
    fun failedTransferPreservesOriginalAndCleansTemp() {
        val destination = FakeDestination(failWrites = true)
        val source = FakeSource("content://phone/1", "clip.mov", "hello".toByteArray())

        val result = VerifiedTransfer(destination, tempName = { ".part" }).move(source)

        assertEquals(Attention.TRANSFER_FAILED, assertIs<TransferResult.NeedsAttention>(result).reason)
        assertTrue(source.deleteCalls.isEmpty())
        assertTrue(destination.files.isEmpty())
        assertEquals(listOf(".part"), destination.deletedTemps)
    }

    @Test
    fun failedSourceDeletionKeepsVerifiedUsbCopyAndDoesNotDeleteAgain() {
        val destination = FakeDestination()
        val source = FakeSource(
            "content://phone/1",
            "clip.mov",
            "hello".toByteArray(),
            deleteResult = SourceDeleteResult.FAILED,
        )

        val result = VerifiedTransfer(destination, tempName = { ".part" }).move(source)

        val attention = assertIs<TransferResult.NeedsAttention>(result)
        assertEquals(Attention.SOURCE_DELETE_FAILED, attention.reason)
        assertEquals("hello", destination.files["clip.mov"]?.decodeToString())
        assertEquals(listOf(SourceDeleteResult.FAILED), source.deleteCalls)
    }

    @Test
    fun disconnectedDestinationPreservesSource() {
        val destination = FakeDestination(failWrites = true, failureMessage = "USB disconnected")
        val source = FakeSource("content://phone/1", "clip.mov", "hello".toByteArray())

        val result = VerifiedTransfer(destination, tempName = { ".part" }).move(source)

        assertEquals(Attention.USB_DISCONNECTED, assertIs<TransferResult.NeedsAttention>(result).reason)
        assertTrue(source.deleteCalls.isEmpty())
    }

    @Test
    fun collisionNeverOverwritesWithoutAnExplicitDecision() {
        val destination = FakeDestination(existing = mapOf("clip.mov" to "old".toByteArray()))
        val source = FakeSource("content://phone/1", "clip.mov", "new".toByteArray())

        val result = VerifiedTransfer(destination).move(source)

        assertIs<TransferResult.Collision>(result)
        assertEquals("old", destination.files["clip.mov"]?.decodeToString())
        assertTrue(source.deleteCalls.isEmpty())
    }
}

private class FakeSource(
    override val uri: String,
    override val name: String,
    private val bytes: ByteArray,
    private val deleteResult: SourceDeleteResult = SourceDeleteResult.DELETED,
) : TransferSource {
    val deleteCalls = mutableListOf<SourceDeleteResult>()

    override fun open(): InputStream = ByteArrayInputStream(bytes)

    override fun delete(): SourceDeleteResult {
        deleteCalls += deleteResult
        return deleteResult
    }
}

private class FakeDestination(
    existing: Map<String, ByteArray> = emptyMap(),
    private val corruptReadBack: Boolean = false,
    private val failWrites: Boolean = false,
    private val failureMessage: String = "write failed",
) : TransferDestination {
    val files = existing.toMutableMap()
    val deletedTemps = mutableListOf<String>()
    private val tempFiles = mutableMapOf<String, ByteArrayOutputStream>()

    override fun find(name: String): String? = if (name in files || name in tempFiles) name else null

    override fun createTemp(name: String): String {
        tempFiles[name] = ByteArrayOutputStream()
        return name
    }

    override fun openForWrite(uri: String): OutputStream {
        if (failWrites) throw IllegalStateException(failureMessage)
        return tempFiles[uri] ?: error("missing temp")
    }

    override fun openForRead(uri: String): InputStream {
        val bytes = tempFiles[uri]?.toByteArray() ?: files[uri] ?: error("missing file")
        return ByteArrayInputStream(if (corruptReadBack) bytes + byteArrayOf(1) else bytes)
    }

    override fun renameTemp(uri: String, finalName: String): String {
        check(finalName !in files) { "overwrite" }
        files[finalName] = tempFiles.remove(uri)!!.toByteArray()
        return finalName
    }

    override fun deleteTemp(uri: String): Boolean {
        deletedTemps += uri
        tempFiles.remove(uri)
        return true
    }
}
