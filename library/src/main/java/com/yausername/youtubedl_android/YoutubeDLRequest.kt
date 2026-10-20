package com.yausername.youtubedl_android

class YoutubeDLRequest {
    private val urls: List<String>
    private val options = YoutubeDLOptions()
    private val customCommandList: MutableList<String> = ArrayList()
    internal var timingTraceId: String? = null
        private set
    internal var timingExecutionKind: String? = null
        private set
    internal var timingListener: ((YoutubeDLTimingEvent) -> Unit)? = null
        private set

    constructor(url: String) {
        urls = listOf(url)
    }

    constructor(urls: List<String>) {
        this.urls = urls
    }

    @JvmOverloads
    fun setTimingListener(
        traceId: String,
        listener: (YoutubeDLTimingEvent) -> Unit,
        executionKind: String = "other",
    ): YoutubeDLRequest {
        require(traceId.matches(Regex("[a-f0-9]{8}"))) {
            "Timing trace IDs must be eight lowercase hexadecimal characters."
        }
        require(executionKind in setOf("metadata", "download", "other")) {
            "Timing execution kind must be metadata, download, or other."
        }
        timingTraceId = traceId
        timingExecutionKind = executionKind
        timingListener = listener
        return this
    }

    fun addOption(option: String, argument: String): YoutubeDLRequest {
        options.addOption(option, argument)
        return this
    }

    fun addOption(option: String, argument: Number): YoutubeDLRequest {
        options.addOption(option, argument)
        return this
    }

    fun addOption(option: String): YoutubeDLRequest {
        options.addOption(option)
        return this
    }

    fun addCommands(commands: List<String>): YoutubeDLRequest {
        customCommandList.addAll(commands)
        return this
    }

    fun getOption(option: String): String? {
        return options.getArgument(option)
    }

    fun getArguments(option: String): List<String?>? {
        return options.getArguments(option)
    }

    fun hasOption(option: String): Boolean {
        return options.hasOption(option)
    }

    fun buildCommand(): List<String> {
        val commandList: MutableList<String> = ArrayList()
        commandList.addAll(options.buildOptions())
        commandList.addAll(customCommandList)
        commandList.addAll(urls)
        return commandList
    }
}