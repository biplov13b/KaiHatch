package com.biplov.kaihatch.ui

import com.biplov.kaihatch.data.ResumeJournalManager
import com.biplov.kaihatch.model.ResumeJournalState

/**
 * Classifies the exact actionable state of an interrupted Hatch Mode recovery card
 * based strictly on verified [ResumeJournalManager.assessResumeEligibility] and
 * [ResumeJournalManager.assessRestoreEligibility] results.
 */
enum class RecoveryCardKind {
    /** Both Resume In-Place Extraction and Restore Remaining ZIP are safely available. */
    BOTH_AVAILABLE,

    /** Only Resume In-Place Extraction is available (e.g., final remaining entry or non-ZIP format). */
    RESUME_ONLY,

    /** Central Directory restoration was interrupted mid-flight; only completing restoration is allowed. */
    RESTORE_PENDING,

    /** Only Restore Remaining ZIP is available. */
    RESTORE_ONLY,

    /** Neither Resume nor Restore is safely available (fail-closed state). */
    UNAVAILABLE
}

data class RecoveryCardState(
    val kind: RecoveryCardKind,
    val showResumeButton: Boolean,
    val showRestoreButton: Boolean,
    val isPendingRestoreRetry: Boolean,
    val remainingEntriesCount: Int,
    val restoreRemainingCount: Int,
    val pendingDeflateTailSwapEntryPath: String?,
    val unavailableReason: String? = null,
    val secondaryReason: String? = null,
    val isSafDirectPosixLimitation: Boolean = false
)

/**
 * Resolves recovery card visibility, messaging, and SAF/direct-POSIX limitation notices
 * directly from verified Resume and Restore eligibility assessments.
 */
fun resolveRecoveryCardState(
    resumeState: ResumeJournalState,
    resumeEligibility: ResumeJournalManager.ResumeEligibility,
    restoreEligibility: ResumeJournalManager.RestoreEligibility
): RecoveryCardState {
    val restorable = restoreEligibility as? ResumeJournalManager.RestoreEligibility.Restorable
    val isPendingRestore = resumeState.hasPendingRestoration || (restorable?.hasPendingRestoreCheckpoint == true)
    val canResume = (resumeEligibility is ResumeJournalManager.ResumeEligibility.Resumable) && !isPendingRestore
    val canRestore = restorable != null

    val remainingEntriesCount = (resumeState.totalEntries - resumeState.completedPaths.size)
        .coerceAtLeast(resumeState.remainingEntries.size)
        .coerceAtLeast(0)
    val restoreRemainingCount = restorable?.remainingCount ?: remainingEntriesCount

    val resumeFailReason = (resumeEligibility as? ResumeJournalManager.ResumeEligibility.Unrecoverable)?.reason
    val restoreFailReason = when (restoreEligibility) {
        is ResumeJournalManager.RestoreEligibility.Unrecoverable -> restoreEligibility.reason
        is ResumeJournalManager.RestoreEligibility.UnsupportedFormat -> restoreEligibility.reason
        is ResumeJournalManager.RestoreEligibility.Restorable -> null
    }

    val combinedReasons = listOfNotNull(resumeFailReason, restoreFailReason).joinToString(" ")
    val isSafLimitation = combinedReasons.contains("direct-POSIX", ignoreCase = true) ||
        Regex("\\bSAF\\b").containsMatchIn(combinedReasons) ||
        combinedReasons.contains("fsync", ignoreCase = true) ||
        combinedReasons.contains("in-place truncation", ignoreCase = true) ||
        combinedReasons.contains("read-write", ignoreCase = true) ||
        combinedReasons.contains("ParcelFileDescriptor", ignoreCase = true) ||
        combinedReasons.contains("provider", ignoreCase = true)

    val kind = when {
        canResume && canRestore -> RecoveryCardKind.BOTH_AVAILABLE
        !canResume && canRestore && isPendingRestore -> RecoveryCardKind.RESTORE_PENDING
        !canResume && canRestore -> RecoveryCardKind.RESTORE_ONLY
        canResume && !canRestore -> RecoveryCardKind.RESUME_ONLY
        else -> RecoveryCardKind.UNAVAILABLE
    }

    val unavailableReason = if (kind == RecoveryCardKind.UNAVAILABLE) {
        when {
            !resumeFailReason.isNullOrBlank() && !restoreFailReason.isNullOrBlank() &&
                !resumeFailReason.equals(restoreFailReason, ignoreCase = true) &&
                !restoreFailReason.contains(resumeFailReason, ignoreCase = true) &&
                !resumeFailReason.contains(restoreFailReason, ignoreCase = true) ->
                "Resume unavailable: $resumeFailReason\nRestore unavailable: $restoreFailReason"
            !resumeFailReason.isNullOrBlank() -> resumeFailReason
            !restoreFailReason.isNullOrBlank() -> restoreFailReason
            else -> "Recovery state could not be verified safely; preserving files on disk."
        }
    } else null

    val secondaryReason = when (kind) {
        RecoveryCardKind.RESUME_ONLY -> restoreFailReason
        RecoveryCardKind.RESTORE_ONLY -> resumeFailReason
        else -> null
    }

    return RecoveryCardState(
        kind = kind,
        showResumeButton = canResume,
        showRestoreButton = canRestore,
        isPendingRestoreRetry = isPendingRestore,
        remainingEntriesCount = remainingEntriesCount,
        restoreRemainingCount = restoreRemainingCount,
        pendingDeflateTailSwapEntryPath = restorable?.pendingDeflateTailSwapEntryPath,
        unavailableReason = unavailableReason,
        secondaryReason = secondaryReason,
        isSafDirectPosixLimitation = isSafLimitation
    )
}
