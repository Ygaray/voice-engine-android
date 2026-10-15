package io.github.ygaray.sttengine.internal

/** RED stub — replaced by the D-06 classifier. */
internal enum class ConnectFailureClass { FastFail, Retryable }

internal class ConnectObservation(
    val throwable: Throwable? = null,
    val responseCode: Int? = null,
    val closeCode: Int? = null,
    val serverErrorCode: String? = null,
    val sawOpen: Boolean = false,
)

internal fun classifyConnectFailure(observation: ConnectObservation): ConnectFailureClass =
    ConnectFailureClass.FastFail
