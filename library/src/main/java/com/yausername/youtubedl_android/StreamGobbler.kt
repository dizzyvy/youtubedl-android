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
    private val invocationId: String? = null,
    private val executionKind: String? = null,
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
        if (
            (fields.size != LEGACY_FIELD_COUNT && fields.size != TIMING_FIELD_COUNT) ||
            fields[0] != "RIFT_TIMING" ||
            fields[1] != traceId
        ) {
            return false
        }

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
        val hasExtendedFields = fields.size == TIMING_FIELD_COUNT
        val eventInvocationId =
            if (hasExtendedFields) fields[9].takeIf { it.isNotEmpty() } ?: return false else null
        if (eventInvocationId != null && eventInvocationId != invocationId) return false
        val eventExecutionKind =
            if (hasExtendedFields) fields[10].takeIf { it.isNotEmpty() } ?: return false else null
        if (eventExecutionKind != null && eventExecutionKind != executionKind) return false
        val parentOperationId =
            if (!hasExtendedFields || fields[11].isEmpty()) {
                null
            } else {
                fields[11].toLongOrNull()?.takeIf { it >= 0 } ?: return false
            }
        val extractorKey =
            if (hasExtendedFields) fields[12].takeIf { it.isNotEmpty() }?.also {
                if (!SAFE_IDENTIFIER.matches(it)) return false
            } else null
        val client =
            if (hasExtendedFields) fields[13].takeIf { it.isNotEmpty() }?.also {
                if (!SAFE_IDENTIFIER.matches(it)) return false
            } else null
        val provider =
            if (hasExtendedFields) fields[14].takeIf { it.isNotEmpty() }?.also {
                if (!SAFE_IDENTIFIER.matches(it)) return false
            } else null
        val relatedOperationId =
            if (!hasExtendedFields || fields[15].isEmpty()) {
                null
            } else {
                fields[15].toLongOrNull()?.takeIf { it >= 0 } ?: return false
            }
        val exitCode =
            if (!hasExtendedFields || fields[16].isEmpty()) {
                null
            } else {
                fields[16].toIntOrNull() ?: return false
            }
        val exitStatus =
            if (hasExtendedFields) fields[17].takeIf { it.isNotEmpty() && it != "-" }?.also {
                if (it !in EXIT_STATUSES) return false
            } else null
        val exitStatusIsConsistent =
            when (exitStatus) {
                null -> true
                "none" -> exitCode == null && outcome == "success"
                "zero" -> exitCode == 0 && outcome == "success"
                "nonzero" -> (exitCode == null || exitCode != 0) && outcome == "failed"
                "boolean", "non_integer" -> exitCode == null && outcome == "failed"
                else -> false
            }
        if (!exitStatusIsConsistent) {
            return false
        }

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
                invocationId = eventInvocationId,
                executionKind = eventExecutionKind,
                parentOperationId = parentOperationId,
                extractorKey = extractorKey,
                client = client,
                provider = provider,
                relatedOperationId = relatedOperationId,
                exitCode = exitCode,
                exitStatus = exitStatus,
            ),
        )
        return true
    }

    companion object {
        private val TAG = StreamGobbler::class.java.simpleName
        private val SAFE_EXCEPTION_TYPE = Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")
        private val SAFE_IDENTIFIER = Regex("[A-Za-z0-9_.-]{1,64}")
        private val LEGACY_FIELD_COUNT = 9
        private val TIMING_FIELD_COUNT = 18
        private val EXIT_STATUSES = setOf("none", "zero", "nonzero", "boolean", "non_integer")
        private val TIMING_EVENTS =
            setOf(
                "network_request_started",
                "network_request_finished",
                "response_processing_started",
                "response_processing_finished",
                "response_transport_started",
                "response_transport_finished",
                "response_body_read_started",
                "response_body_read_finished",
                "response_decode_started",
                "response_decode_finished",
                "response_parse_started",
                "response_parse_finished",
                "response_json_parse_started",
                "response_json_parse_finished",
                "extractor_started",
                "extractor_finished",
                "extractor_initialization_started",
                "extractor_initialization_finished",
                "youtube_client_attempt_started",
                "youtube_client_attempt_finished",
                "po_token_lookup_started",
                "po_token_lookup_finished",
                "po_token_provider_started",
                "po_token_provider_finished",
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
            setOf(
                "request",
                "response_processing",
                "extractor",
                "download",
                "postprocess",
                "retry",
                "process",
            )
    }
}