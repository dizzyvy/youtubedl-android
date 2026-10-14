package com.yausername.youtubedl_android

import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.nio.charset.StandardCharsets

internal class StreamGobbler(
    private val buffer: StringBuffer,
    private val stream: InputStream,
    private val timingTraceId: String? = null,
    private val timingListener: ((YoutubeDLTimingEvent) -> Unit)? = null,
) : Thread() {
    init {
        start()
    }

    override fun run() {
        try {
            val `in`: Reader = InputStreamReader(stream, StandardCharsets.UTF_8)
            val line = StringBuilder()
            var nextChar: Int
            while (`in`.read().also { nextChar = it } != -1) {
                val char = nextChar.toChar()
                line.append(char)
                if (char == '\n') {
                    val lineText = line.toString()
                    if (!consumeTimingEvent(lineText)) buffer.append(lineText)
                    line.setLength(0)
                }
            }
            if (line.isNotEmpty()) buffer.append(line)
        } catch (e: IOException) {
            Log.e(TAG, "failed to read stream", e)
        }
    }

    private fun consumeTimingEvent(line: String): Boolean {
        val traceId = timingTraceId ?: return false
        val listener = timingListener ?: return false
        val fields = line.trimEnd('\r', '\n').split('\t')
        if (fields.size != 9 || fields[0] != "RIFT_TIMING" || fields[1] != traceId) return false

        val name = fields[2]
        if (name !in TIMING_EVENTS) return false
        val operationId = fields[3].toLongOrNull()?.takeIf { it >= 0 } ?: return false
        val monotonicTimeNs = fields[4].toLongOrNull()?.takeIf { it > 0 } ?: return false
        val value = fields[5].toLongOrNull()?.takeIf { it >= 0 } ?: return false
        val outcome = fields[6]
        if (outcome !in TIMING_OUTCOMES) return false
        val exceptionType = fields[7].takeIf { it.isNotEmpty() }
        if (exceptionType != null && !SAFE_EXCEPTION_TYPE.matches(exceptionType)) return false
        val category = fields[8].takeIf { it.isNotEmpty() }
        if (category != null && category !in TIMING_CATEGORIES) return false

        listener(
            YoutubeDLTimingEvent(
                name = name,
                operationId = operationId,
                monotonicTimeNs = monotonicTimeNs,
                clockSource = "yt_dlp_monotonic",
                durationMs = if (name.endsWith("_finished")) value else null,
                outcome = outcome,
                exceptionType = exceptionType,
                category = category,
                value = if (name == "retry_reported") value else null,
            ),
        )
        return true
    }

    companion object {
        private val TAG = StreamGobbler::class.java.simpleName
        private val SAFE_EXCEPTION_TYPE = Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")
        private val TIMING_EVENTS =
            setOf(
                "network_request_started",
                "network_request_finished",
                "extractor_started",
                "extractor_finished",
                "download_transfer_started",
                "download_transfer_finished",
                "postprocessor_started",
                "postprocessor_finished",
                "yt_dlp_execution_started",
                "yt_dlp_execution_finished",
                "retry_reported",
            )
        private val TIMING_OUTCOMES =
            setOf("started", "success", "failed", "retrying", "exhausted", "not_retried")
        private val TIMING_CATEGORIES =
            setOf("request", "extractor", "download", "postprocess", "retry", "process")
    }
}