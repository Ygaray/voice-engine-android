package io.github.ygaray.sttengine.transcription

import java.io.File

/**
 * The batch-transcription protocol client (HUB-01): `POST /jobs` (submit) + `GET /jobs/{id}`
 * (poll). Extracted and hardened from the internal `:voicenotes` implementation of the same
 * Phase-7 server contract — see `server/README.md`'s `POST /jobs`/`GET /jobs/{id}` section for the
 * authoritative wire contract this client implements.
 */
public interface TranscriptionClient {
    /**
     * Submits [file] (declared as [mimeType]) to `POST /jobs` for batch transcription.
     *
     * [language] defaults to `"en"`, which reproduces the behavior of the previously published tag
     * exactly — a caller that never passes it sees no change. The server validates the value against
     * its own allow-list and rejects anything else with a typed [SubmitOutcome.Rejected]; the two
     * codes supported at this tag are English (`"en"`) and Spanish (`"es"`).
     */
    public suspend fun submitJob(file: File, mimeType: String, language: String = "en"): SubmitOutcome
    public suspend fun getJobStatus(jobId: String): JobStatusOutcome
}

/** The outcome of a `POST /jobs` attempt. */
public sealed interface SubmitOutcome {
    /** `202` — the server accepted the upload and assigned a job id. */
    public class Accepted(public val jobId: String) : SubmitOutcome {
        override fun equals(other: Any?): Boolean =
            this === other || (other is Accepted && jobId == other.jobId)
        override fun hashCode(): Int = jobId.hashCode()
        override fun toString(): String = "Accepted(jobId=$jobId)"
    }

    /** `413`/`400` — the server rejected the upload outright. A permanent, typed failure. */
    public class Rejected(
        public val code: TranscriptionErrorCode,
        public val message: String,
    ) : SubmitOutcome {
        override fun equals(other: Any?): Boolean =
            this === other || (other is Rejected && code == other.code && message == other.message)
        override fun hashCode(): Int = 31 * code.hashCode() + message.hashCode()
        override fun toString(): String = "Rejected(code=$code, message=$message)"
    }

    /** Any network failure or unexpected status (including `408`) — retry, do not mark permanent. */
    public data object Transient : SubmitOutcome
}

/** The outcome of a `GET /jobs/{id}` poll. */
public sealed interface JobStatusOutcome {
    /** `{status:"done", transcript}` — terminal success. */
    public class Done(public val transcript: String) : JobStatusOutcome {
        override fun equals(other: Any?): Boolean =
            this === other || (other is Done && transcript == other.transcript)
        override fun hashCode(): Int = transcript.hashCode()
        override fun toString(): String = "Done(transcript=$transcript)"
    }

    /** `{status:"failed", error}` — terminal, permanent failure. */
    public class Failed(public val message: String) : JobStatusOutcome {
        override fun equals(other: Any?): Boolean =
            this === other || (other is Failed && message == other.message)
        override fun hashCode(): Int = message.hashCode()
        override fun toString(): String = "Failed(message=$message)"
    }

    /** `404` — the job reference was lost (TTL eviction or server restart), not "failed." */
    public data object NotFound : JobStatusOutcome

    /** `{status:"processing"}` — still running; retry later. */
    public data object Pending : JobStatusOutcome

    /** Network failure or unexpected status — retry, do not touch persisted state. */
    public data object Transient : JobStatusOutcome
}

/**
 * Fresh, `:transcription`-owned error taxonomy — carries the same wire strings as
 * `voicenotes.NoteErrorCode.UPLOAD_TOO_LARGE`/`.UPLOAD_REJECTED` (`"upload-too-large"`/
 * `"upload-rejected"`) but is a genuinely separate type: `:transcription` must never depend on
 * `:voicenotes` (HUB-01's isolation contract).
 */
@JvmInline
public value class TranscriptionErrorCode(public val value: String) {
    public companion object {
        public val UPLOAD_TOO_LARGE: TranscriptionErrorCode = TranscriptionErrorCode("upload-too-large")
        public val UPLOAD_REJECTED: TranscriptionErrorCode = TranscriptionErrorCode("upload-rejected")
    }
}
