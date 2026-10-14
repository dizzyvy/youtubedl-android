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
)
