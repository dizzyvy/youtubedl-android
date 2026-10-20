package com.yausername.youtubedl_android

import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.fasterxml.jackson.databind.ObjectMapper
import com.yausername.youtubedl_android.mapper.VideoInfo
import com.yausername.youtubedl_common.SharedPrefsHelper
import com.yausername.youtubedl_common.SharedPrefsHelper.update
import com.yausername.youtubedl_common.utils.ZipUtils.unzip
import org.apache.commons.io.FileUtils
import java.io.File
import java.io.IOException
import java.util.Collections
import kotlin.collections.set

object YoutubeDL {
    private var initialized = false
    private var pythonPath: File? = null
    private var ffmpegPath: File? = null
    private var quickJsPath: File? = null
    private var ytdlpPath: File? = null
    private var binDir: File? = null
    private var ENV_LD_LIBRARY_PATH: String? = null
    private var ENV_SSL_CERT_FILE: String? = null
    private var ENV_PYTHONHOME: String? = null
    private var TMPDIR: String = ""
    private var timingInstrumentationEnabled = false
    private val idProcessMap = Collections.synchronizedMap(HashMap<String, Process>())

    fun setTimingInstrumentationEnabled(enabled: Boolean) {
        check(!initialized || timingInstrumentationEnabled == enabled) {
            "Timing instrumentation must be configured before YoutubeDL initialization."
        }
        timingInstrumentationEnabled = enabled
    }

    @Synchronized
    @Throws(YoutubeDLException::class)
    fun init(appContext: Context) {
        if (initialized) return
        val baseDir = File(appContext.noBackupFilesDir, baseName)
        if (!baseDir.exists()) baseDir.mkdir()
        val packagesDir = File(baseDir, packagesRoot)
        binDir = File(appContext.applicationInfo.nativeLibraryDir)
        pythonPath = File(binDir, pythonBinName)
        ffmpegPath = File(binDir, ffmpegBinName)
        quickJsPath = File(binDir, quickJsBinName)
        val pythonDir = File(packagesDir, pythonDirName)
        val ffmpegDir = File(packagesDir, ffmpegDirName)
        val aria2cDir = File(packagesDir, aria2cDirName)
        val ytdlpDir = File(baseDir, ytdlpDirName)
        ytdlpPath = File(ytdlpDir, ytdlpBin)
        ENV_LD_LIBRARY_PATH = pythonDir.absolutePath + "/usr/lib" + ":" +
                ffmpegDir.absolutePath + "/usr/lib" + ":" +
                aria2cDir.absolutePath + "/usr/lib"
        ENV_SSL_CERT_FILE = pythonDir.absolutePath + "/usr/etc/tls/cert.pem"
        ENV_PYTHONHOME = pythonDir.absolutePath + "/usr"
        TMPDIR = appContext.cacheDir.absolutePath
        initPython(appContext, pythonDir)
        init_ytdlp(appContext, ytdlpDir)
        initialized = true
    }

    @Throws(YoutubeDLException::class)
    fun init_ytdlp(appContext: Context, ytdlpDir: File) {
        if (!ytdlpDir.exists()) ytdlpDir.mkdirs()
        val ytdlpBinary = File(ytdlpDir, ytdlpBin)
        if (timingInstrumentationEnabled) {
            installInstrumentedYtDlp(appContext, ytdlpDir, ytdlpBinary)
            return
        }
        if (!ytdlpBinary.exists()) {
            try {
                val inputStream =
                    appContext.resources.openRawResource(R.raw.ytdlp) /* will be renamed to yt-dlp */
                FileUtils.copyInputStreamToFile(inputStream, ytdlpBinary)
            } catch (e: Exception) {
                FileUtils.deleteQuietly(ytdlpDir)
                throw YoutubeDLException("failed to initialize", e)
            }
        }
    }

    private fun installInstrumentedYtDlp(
        appContext: Context,
        ytdlpDir: File,
        ytdlpBinary: File,
    ) {
        val marker = File(ytdlpDir, ".rift-timing-runtime")
        val candidate = File(ytdlpDir, "$ytdlpBin.instrumented")
        val backup = File(ytdlpDir, "$ytdlpBin.previous")
        if (!ytdlpBinary.exists() && backup.exists()) backup.renameTo(ytdlpBinary)
        if (ytdlpBinary.isFile && marker.isFile &&
            marker.readText() == INSTRUMENTED_YTDLP_VERSION
        ) {
            FileUtils.deleteQuietly(backup)
            return
        }

        try {
            appContext.resources.openRawResource(R.raw.ytdlp_instrumented).use { input ->
                FileUtils.copyInputStreamToFile(input, candidate)
            }
            FileUtils.deleteQuietly(backup)
            val hadPreviousBinary = ytdlpBinary.exists()
            if (hadPreviousBinary && !ytdlpBinary.renameTo(backup)) {
                throw IOException("Could not preserve the existing yt-dlp runtime.")
            }
            if (!candidate.renameTo(ytdlpBinary)) {
                if (hadPreviousBinary) backup.renameTo(ytdlpBinary)
                throw IOException("Could not install the instrumented yt-dlp runtime.")
            }
            marker.writeText(INSTRUMENTED_YTDLP_VERSION)
            FileUtils.deleteQuietly(backup)
        } catch (error: Exception) {
            FileUtils.deleteQuietly(candidate)
            if (!ytdlpBinary.exists() && backup.exists()) backup.renameTo(ytdlpBinary)
            throw YoutubeDLException("failed to initialize instrumented yt-dlp", error)
        }
    }

    @Throws(YoutubeDLException::class)
    fun initPython(appContext: Context, pythonDir: File) {
        val pythonLib = File(binDir, pythonLibName)
        // using size of lib as version
        val pythonSize = pythonLib.length().toString()
        if (!pythonDir.exists() || shouldUpdatePython(appContext, pythonSize)) {
            FileUtils.deleteQuietly(pythonDir)
            pythonDir.mkdirs()
            try {
                unzip(pythonLib, pythonDir)
            } catch (e: Exception) {
                FileUtils.deleteQuietly(pythonDir)
                throw YoutubeDLException("failed to initialize", e)
            }
            updatePython(appContext, pythonSize)
        }
    }

    private fun shouldUpdatePython(appContext: Context, version: String): Boolean {
        return version != SharedPrefsHelper[appContext, pythonLibVersion]
    }

    private fun updatePython(appContext: Context, version: String) {
        update(appContext, pythonLibVersion, version)
    }

    private fun assertInit() {
        check(initialized) { "instance not initialized" }
    }

    @Throws(YoutubeDLException::class, InterruptedException::class, CanceledException::class)
    fun getInfo(url: String): VideoInfo {
        val request = YoutubeDLRequest(url)
        return getInfo(request)
    }

    @Throws(YoutubeDLException::class, InterruptedException::class, CanceledException::class)
    fun getInfo(request: YoutubeDLRequest): VideoInfo {
        request.addOption("--dump-json")
        val response = execute(request, null, null)
        val videoInfo: VideoInfo = try {
            objectMapper.readValue(response.out, VideoInfo::class.java)
        } catch (e: IOException) {
            throw YoutubeDLException("Unable to parse video information", e)
        } ?: throw YoutubeDLException("Failed to fetch video information")
        return videoInfo
    }

    private fun ignoreErrors(request: YoutubeDLRequest, out: String): Boolean {
        return request.hasOption("--dump-json") && !out.isEmpty() && request.hasOption("--ignore-errors")
    }

    fun destroyProcessById(id: String): Boolean {
        if (idProcessMap.containsKey(id)) {
            val p = idProcessMap[id]
            var alive = true

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                alive = p!!.isAlive
            }
            if (alive) {
                destroyChildProcesses(id)
                p?.destroy()
                idProcessMap.remove(id)
                return true
            }
        }
        return false
    }

    private fun destroyChildProcesses(id: String) : Boolean {
        try {
            val command = "pstree -p $id | grep -oP '\\(\\K[^\\)]+' | xargs kill"
            val processBuilder = ProcessBuilder("/system/bin/sh", "-c", command)
            val process = processBuilder.start()
            val res = process.waitFor()
            return res == 0
        }catch (e: Exception) {
            return false
        }
    }

    class CanceledException : Exception()

    @Throws(YoutubeDLException::class, InterruptedException::class, CanceledException::class)
    fun execute(
        request: YoutubeDLRequest,
        processId: String? = null,
        callback: ((Float, Long, String) -> Unit)? = null
    ): YoutubeDLResponse {
        return executeImpl(request, processId, false, callback)
    }

    @JvmOverloads
    @Throws(YoutubeDLException::class, InterruptedException::class, CanceledException::class)
    fun execute(
        request: YoutubeDLRequest,
        processId: String? = null,
        redirectErrorStream: Boolean = false,
        callback: ((Float, Long, String) -> Unit)? = null
    ): YoutubeDLResponse {
        return executeImpl(request, processId, redirectErrorStream, callback)
    }

    @Throws(YoutubeDLException::class, InterruptedException::class, CanceledException::class)
    private fun executeImpl(
        request: YoutubeDLRequest,
        processId: String? = null,
        redirectErrorStream: Boolean = false,
        callback: ((Float, Long, String) -> Unit)? = null
    ) : YoutubeDLResponse {
        val invocationId =
            java.util.UUID.randomUUID().toString().replace("-", "")
        val executionKind = request.timingExecutionKind

        fun beginTimingSpan(name: String): Long? {
            val listener = request.timingListener ?: return null
            val now = SystemClock.elapsedRealtimeNanos()
            listener(
                YoutubeDLTimingEvent(
                    name = "${name}_started",
                    operationId = 0,
                    monotonicTimeNs = now,
                    clockSource = "android_elapsed_realtime",
                    durationMs = null,
                    outcome = "started",
                    exceptionType = null,
                    category = null,
                    value = null,
                    invocationId = invocationId,
                    executionKind = executionKind,
                ),
            )
            return now
        }

        fun finishTimingSpan(
            name: String,
            startedAt: Long?,
            outcome: String = "success",
            error: Throwable? = null,
        ) {
            val listener = request.timingListener ?: return
            if (startedAt == null) return
            val now = SystemClock.elapsedRealtimeNanos()
            listener(
                YoutubeDLTimingEvent(
                    name = "${name}_finished",
                    operationId = 0,
                    monotonicTimeNs = now,
                    clockSource = "android_elapsed_realtime",
                    durationMs = (now - startedAt) / 1_000_000,
                    outcome = outcome,
                    exceptionType = error?.javaClass?.simpleName?.takeIf { it.isNotBlank() },
                    category = null,
                    value = null,
                    invocationId = invocationId,
                    executionKind = executionKind,
                ),
            )
        }

        fun reportTimingEvent(name: String, outcome: String, value: Long? = null) {
            val listener = request.timingListener ?: return
            listener(
                YoutubeDLTimingEvent(
                    name = name,
                    operationId = 0,
                    monotonicTimeNs = SystemClock.elapsedRealtimeNanos(),
                    clockSource = "android_elapsed_realtime",
                    durationMs = null,
                    outcome = outcome,
                    exceptionType = null,
                    category = null,
                    value = value,
                    invocationId = invocationId,
                    executionKind = executionKind,
                ),
            )
        }

        assertInit()
        if (processId != null && idProcessMap.containsKey(processId)) throw YoutubeDLException("Process ID already exists")
        // disable caching unless explicitly requested
        if (!request.hasOption("--cache-dir") || request.getOption("--cache-dir") == null) {
            request.addOption("--no-cache-dir")
        }

        if (request.buildCommand().contains("libaria2c.so")) {
            request
                .addOption("--external-downloader-args", "aria2c:--summary-interval=1")
                .addOption(
                    "--external-downloader-args",
                    "aria2c:--ca-certificate=$ENV_SSL_CERT_FILE"
                )
        }

        request.addOption("--js-runtimes", "quickjs:${quickJsPath!!.absolutePath}")

        /* Set ffmpeg location, See https://github.com/xibr/ytdlp-lazy/issues/1 */
        request.addOption("--ffmpeg-location", ffmpegPath!!.absolutePath)
        val youtubeDLResponse: YoutubeDLResponse
        val process: Process
        val exitCode: Int
        val outBuffer = StringBuffer() //stdout
        val errBuffer = StringBuffer() //stderr
        val startTime = System.currentTimeMillis()
        val args = request.buildCommand()
        val command: MutableList<String?> = ArrayList()
        command.addAll(listOf(pythonPath!!.absolutePath, ytdlpPath!!.absolutePath))
        command.addAll(args)
        val processBuilder = ProcessBuilder(command)
            .redirectErrorStream(redirectErrorStream)

        processBuilder.environment().apply {
            this["LD_LIBRARY_PATH"] = ENV_LD_LIBRARY_PATH
            this["SSL_CERT_FILE"] = ENV_SSL_CERT_FILE
            this["PATH"] = System.getenv("PATH") + ":" + binDir!!.absolutePath
            this["PYTHONHOME"] = ENV_PYTHONHOME
            this["HOME"] = ENV_PYTHONHOME
            this["TMPDIR"] = TMPDIR
            request.timingTraceId?.let {
                this["RIFT_DOWNLOADER_TRACE_ID"] = it
                this["RIFT_DOWNLOADER_INVOCATION_ID"] = invocationId
                this["RIFT_DOWNLOADER_EXECUTION_KIND"] = executionKind ?: "other"
            }
        }

        val processLaunchStartedAt = beginTimingSpan("wrapper_process_launch")
        process = try {
            processBuilder.start()
        } catch (e: IOException) {
            finishTimingSpan("wrapper_process_launch", processLaunchStartedAt, "failed", e)
            throw YoutubeDLException(e)
        }
        finishTimingSpan("wrapper_process_launch", processLaunchStartedAt)
        if (processId != null) {
            idProcessMap[processId] = process
        }
        val outStream = process.inputStream
        val errStream = process.errorStream
        val stdoutReaderStartedAt = beginTimingSpan("wrapper_stdout_reader")
        val stdOutProcessor = StreamProcessExtractor(outBuffer, outStream, callback)
        val stderrReaderStartedAt = beginTimingSpan("wrapper_stderr_reader")
        val stdErrProcessor =
            StreamGobbler(
                buffer = errBuffer,
                stream = errStream,
                timingTraceId = request.timingTraceId,
                invocationId = invocationId,
                executionKind = executionKind,
                timingListener = request.timingListener,
            )
        var stdoutReaderFinished = false
        var stderrReaderFinished = false
            var processWaitStartedAt: Long? = null
            exitCode = try {
                stdOutProcessor.join()
                finishTimingSpan("wrapper_stdout_reader", stdoutReaderStartedAt)
            stdoutReaderFinished = true
            stdErrProcessor.join()
            finishTimingSpan("wrapper_stderr_reader", stderrReaderStartedAt)
            stderrReaderFinished = true
            processWaitStartedAt = beginTimingSpan("wrapper_process_wait")
            process.waitFor().also {
                finishTimingSpan("wrapper_process_wait", processWaitStartedAt)
            }
        } catch (e: InterruptedException) {
            if (!stdoutReaderFinished) {
                finishTimingSpan("wrapper_stdout_reader", stdoutReaderStartedAt, "interrupted", e)
            }
            if (!stderrReaderFinished) {
                finishTimingSpan("wrapper_stderr_reader", stderrReaderStartedAt, "interrupted", e)
            }
            finishTimingSpan("wrapper_process_wait", processWaitStartedAt, "interrupted", e)
            process.destroy()
            if (processId != null) idProcessMap.remove(processId)
            throw e
        }
        val processWasCanceled = processId != null && !idProcessMap.containsKey(processId)
        reportTimingEvent(
            name = "wrapper_process_exited",
            outcome =
                when {
                    processWasCanceled -> "cancelled"
                    exitCode == 0 -> "success"
                    else -> "failed"
                },
            value = exitCode.toLong(),
        )
        val out = outBuffer.toString()
        val err = errBuffer.toString()
        if (exitCode > 0) {
            if (processId != null && !idProcessMap.containsKey(processId))
                throw CanceledException()
            if (!ignoreErrors(request, out)) {
                idProcessMap.remove(processId)
                throw YoutubeDLException(err)
            }
        }
        idProcessMap.remove(processId)

        val elapsedTime = System.currentTimeMillis() - startTime
        youtubeDLResponse = YoutubeDLResponse(command, exitCode, elapsedTime, out, err)
        return youtubeDLResponse
    }

    @Synchronized
    @Throws(YoutubeDLException::class)
    fun updateYoutubeDL(
        appContext: Context,
        updateChannel: UpdateChannel = UpdateChannel.STABLE
    ): UpdateStatus? {
        assertInit()
        if (timingInstrumentationEnabled) return UpdateStatus.ALREADY_UP_TO_DATE
        return try {
            YoutubeDLUpdater.update(appContext, updateChannel)
        } catch (e: IOException) {
            throw YoutubeDLException("failed to update youtube-dl", e)
        }
    }

    fun version(appContext: Context?): String? {
        return YoutubeDLUpdater.version(appContext)
    }

    fun versionName(appContext: Context?): String? {
        return YoutubeDLUpdater.versionName(appContext)
    }

    enum class UpdateStatus {
        DONE, ALREADY_UP_TO_DATE
    }

    open class UpdateChannel(val apiUrl: String) {
        object STABLE : UpdateChannel("https://api.github.com/repos/yt-dlp/yt-dlp/releases/latest")
        object NIGHTLY :
            UpdateChannel("https://api.github.com/repos/yt-dlp/yt-dlp-nightly-builds/releases/latest")
        object MASTER :
            UpdateChannel("https://api.github.com/repos/yt-dlp/yt-dlp-master-builds/releases/latest")

        companion object {
            @JvmField
            val _STABLE: STABLE = STABLE

            @JvmField
            val _NIGHTLY: NIGHTLY = NIGHTLY

            @JvmField
            val _MASTER: MASTER = MASTER
        }
    }


    const val baseName = "youtubedl-android"
    private const val packagesRoot = "packages"
    private const val pythonBinName = "libpython.so"
    private const val pythonLibName = "libpython.zip.so"
    private const val pythonDirName = "python"
    private const val ffmpegDirName = "ffmpeg"
    private const val ffmpegBinName = "libffmpeg.so"
    private const val quickJsBinName = "libqjs.so"
    private const val aria2cDirName = "aria2c"
    const val ytdlpDirName = "yt-dlp"
    const val ytdlpBin = "yt-dlp"
    const val INSTRUMENTED_YTDLP_VERSION = "2026.10.10.233141-rift-timing"
    private const val pythonLibVersion = "pythonLibVersion"
    val objectMapper = ObjectMapper()

    @JvmStatic
    fun getInstance() = this
}