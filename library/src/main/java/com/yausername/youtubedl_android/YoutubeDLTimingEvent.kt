package com.yausername.youtubedl_android

data class YoutubeDLTimingEvent(
    val name: String,
    val operationId: Long,
    val monotonicTimeNs: Long,
    val clockSource: String,
    val durationMs: Long?,
    val outcome: String,
    val exceptionType: String?,
    val category: String?,
    val value: Long?,
    val invocationId: String? = null,
    val executionKind: String? = null,
    val parentOperationId: Long? = null,
    val extractorKey: String? = null,
    val client: String? = null,
    val provider: String? = null,
    val relatedOperationId: Long? = null,
    val exitCode: Int? = null,
    val exitStatus: String? = null,
)
