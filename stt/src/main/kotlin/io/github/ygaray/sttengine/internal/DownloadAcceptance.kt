package io.github.ygaray.sttengine.internal

/** RED stub — replaced in the GREEN commit. */
internal interface DownloadOutcomeSink {
    fun onProgress()
    fun onSuccess()
    fun onScheduled()
    fun onError(error: Int)
}

/** RED stub — replaced in the GREEN commit. */
@Suppress("UNUSED_PARAMETER")
internal suspend fun awaitDownloadAcceptance(
    timeoutMs: Long,
    register: (DownloadOutcomeSink) -> Unit,
): Boolean = false
