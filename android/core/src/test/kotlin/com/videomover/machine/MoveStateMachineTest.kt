package com.videomover.machine

import com.videomover.model.OutputResolution
import com.videomover.model.TransferMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MoveStateMachineTest {
    @Test
    fun happyPathIsMoveThenDeleteThenConvert() {
        var job = FileJob()
        job = applied(transition(job, MoveCommand.START_TRANSFER))
        assertEquals(FileState.TRANSFERRING, job.state)
        job = applied(transition(job, MoveCommand.MARK_TRANSFER_VERIFIED))
        assertEquals(FileState.TRANSFER_VERIFIED, job.state)
        assertFalse(job.sourceDeleted)

        val armed = transition(job, MoveCommand.ARM_SOURCE_DELETE)
        assertTrue(deleteShouldRun(job, armed))
        job = applied(armed)
        assertTrue(job.deleteDispatched)

        job = applied(transition(job, MoveCommand.MARK_SOURCE_DELETED))
        assertEquals(FileState.SOURCE_DELETED, job.state)
        assertTrue(job.sourceDeleted)

        job = applied(transition(job, MoveCommand.START_CONVERT))
        assertEquals(FileState.CONVERTING, job.state)
        job = applied(transition(job, MoveCommand.MARK_CONVERTED))
        assertEquals(FileState.CONVERTED, job.state)
    }

    @Test
    fun sourceIsNeverDeletedTwiceOrBeforeVerification() {
        val pending = FileJob()
        assertIs<Transition.Rejected>(transition(pending, MoveCommand.ARM_SOURCE_DELETE))
        assertFalse(deleteShouldRun(pending, transition(pending, MoveCommand.ARM_SOURCE_DELETE)))

        val transferring = FileJob(state = FileState.TRANSFERRING)
        assertIs<Transition.Rejected>(transition(transferring, MoveCommand.ARM_SOURCE_DELETE))
        assertIs<Transition.Rejected>(transition(transferring, MoveCommand.MARK_SOURCE_DELETED))

        val verified = FileJob(state = FileState.TRANSFER_VERIFIED)
        assertIs<Transition.Rejected>(transition(verified, MoveCommand.MARK_SOURCE_DELETED))

        val armed = applied(transition(verified, MoveCommand.ARM_SOURCE_DELETE))
        val secondArm = transition(armed, MoveCommand.ARM_SOURCE_DELETE)
        assertFalse(deleteShouldRun(armed, secondArm))
        assertFalse((secondArm as Transition.Applied).changed)

        val deleted = applied(transition(armed, MoveCommand.MARK_SOURCE_DELETED))
        val again = transition(deleted, MoveCommand.MARK_SOURCE_DELETED)
        assertFalse((again as Transition.Applied).changed)
        assertFalse(deleteShouldRun(deleted, transition(deleted, MoveCommand.ARM_SOURCE_DELETE)))
        assertIs<Transition.Rejected>(transition(deleted, MoveCommand.RETRY_DELETE))
    }

    @Test
    fun failedDeleteKeepsBothCopiesAndDoesNotAutoRetry() {
        val armed = FileJob(state = FileState.TRANSFER_VERIFIED, deleteDispatched = true)
        val failed = applied(transition(armed, MoveCommand.FAIL_SOURCE_DELETE, detail = "User dismissed the dialog."))
        assertEquals(FileState.NEEDS_ATTENTION, failed.state)
        assertEquals(AttentionReason.DELETE_FAILED, failed.attentionReason)
        assertFalse(failed.sourceDeleted)
        assertTrue(failed.deleteDispatched)
        assertFalse(deleteShouldRun(failed, transition(failed, MoveCommand.ARM_SOURCE_DELETE)))

        val retried = applied(transition(failed, MoveCommand.RETRY_DELETE))
        assertEquals(FileState.TRANSFER_VERIFIED, retried.state)
        assertFalse(retried.deleteDispatched)
        assertTrue(deleteShouldRun(retried, transition(retried, MoveCommand.ARM_SOURCE_DELETE)))
    }

    @Test
    fun repeatedVerifiedAndConvertMarksAreIdempotent() {
        val transferring = FileJob(state = FileState.TRANSFERRING)
        val once = applied(transition(transferring, MoveCommand.MARK_TRANSFER_VERIFIED))
        val twice = transition(once, MoveCommand.MARK_TRANSFER_VERIFIED)
        assertFalse((twice as Transition.Applied).changed)
        assertEquals(once, twice.job)

        val converting = FileJob(state = FileState.CONVERTING, sourceDeleted = true)
        val done = applied(transition(converting, MoveCommand.MARK_CONVERTED))
        val doneAgain = transition(done, MoveCommand.MARK_CONVERTED)
        assertFalse((doneAgain as Transition.Applied).changed)

        val startAgain = transition(converting, MoveCommand.START_CONVERT)
        assertFalse((startAgain as Transition.Applied).changed)
    }

    @Test
    fun cannotSkipAhead() {
        val pending = FileJob()
        assertIs<Transition.Rejected>(transition(pending, MoveCommand.MARK_CONVERTED))
        assertIs<Transition.Rejected>(transition(pending, MoveCommand.START_CONVERT))
        assertIs<Transition.Rejected>(transition(pending, MoveCommand.MARK_TRANSFER_VERIFIED))
        val verified = FileJob(state = FileState.TRANSFER_VERIFIED)
        assertIs<Transition.Rejected>(transition(verified, MoveCommand.START_CONVERT))
        val deletedButFlagClear = FileJob(state = FileState.SOURCE_DELETED, sourceDeleted = false)
        assertIs<Transition.Rejected>(transition(deletedButFlagClear, MoveCommand.START_CONVERT))
    }

    @Test
    fun retryAfterConversionFailureDoesNotTransferOrDeleteAgain() {
        val converting = FileJob(state = FileState.CONVERTING, sourceDeleted = true, deleteDispatched = true)
        val failed = applied(transition(converting, MoveCommand.FAIL_CONVERT, detail = "Encoder failed."))
        assertTrue(failed.sourceDeleted)
        val retried = applied(transition(failed, MoveCommand.RETRY))
        assertEquals(FileState.SOURCE_DELETED, retried.state)
        assertTrue(retried.sourceDeleted)
        assertTrue(retried.deleteDispatched)
        assertFalse(deleteShouldRun(retried, transition(retried, MoveCommand.ARM_SOURCE_DELETE)))
    }

    @Test
    fun retryAfterTransferFailureStartsOverWithoutADelete() {
        val failing = FileJob(state = FileState.TRANSFERRING)
        val failed = applied(transition(failing, MoveCommand.FAIL_TRANSFER))
        val retried = applied(transition(failed, MoveCommand.RETRY))
        assertEquals(FileState.PENDING, retried.state)
        assertFalse(retried.sourceDeleted)
        assertFalse(retried.deleteDispatched)
    }

    @Test
    fun cancelDoesNotImplyDeletion() {
        val verified = FileJob(state = FileState.TRANSFER_VERIFIED)
        val cancelled = applied(transition(verified, MoveCommand.CANCEL))
        assertEquals(FileState.CANCELLED, cancelled.state)
        assertFalse(cancelled.sourceDeleted)
        val again = transition(cancelled, MoveCommand.CANCEL)
        assertFalse((again as Transition.Applied).changed)
        assertIs<Transition.Rejected>(transition(FileJob(state = FileState.CONVERTED), MoveCommand.CANCEL))
    }

    @Test
    fun attentionPausesOneFileAndResumesTheSameState() {
        val transferring = FileJob(state = FileState.TRANSFERRING)
        val paused = applied(
            transition(transferring, MoveCommand.REQUIRE_ATTENTION, AttentionReason.USB_DISCONNECTED, "Drive removed."),
        )
        assertEquals(FileState.NEEDS_ATTENTION, paused.state)
        assertEquals(FileState.TRANSFERRING, paused.resumeState)
        val resumed = applied(transition(paused, MoveCommand.RESOLVE_ATTENTION))
        assertEquals(FileState.TRANSFERRING, resumed.state)
        assertNullReason(resumed)
    }

    @Test
    fun productAllowsOnlyMoveAndTwoResolutions() {
        assertEquals(listOf(TransferMode.MOVE), TransferMode.entries)
        assertEquals(listOf("480p", "720p"), OutputResolution.entries.map { it.label })
        assertFalse(MoveCommand.entries.any { it.name.contains("COPY") })
    }

    private fun applied(result: Transition): FileJob {
        val applied = assertIs<Transition.Applied>(result)
        assertTrue(applied.changed)
        return applied.job
    }

    private fun assertNullReason(job: FileJob) {
        assertEquals(null, job.attentionReason)
        assertEquals(null, job.errorMessage)
    }
}
