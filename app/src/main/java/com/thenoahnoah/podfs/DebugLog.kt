package com.thenoahnoah.podfs

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DebugLog {
    private const val FILE_NAME = "debug.log"
    private const val MAX_LINES = 500
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
    private val lines = mutableListOf<String>()
    private var file: File? = null

    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        val target = File(context.filesDir, FILE_NAME)
        file = target
        if (target.exists()) lines += target.readLines().takeLast(MAX_LINES)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            append("CRASH on ${thread.name}\n${error.stackTraceToString().trimEnd()}")
            previous?.uncaughtException(thread, error)
        }
        append("App started.")
    }

    @Synchronized
    fun append(message: String): List<String> {
        lines += "${timeFormat.format(Date())}  ${message.trimEnd()}".lines()
        if (lines.size > MAX_LINES) lines.subList(0, lines.size - MAX_LINES).clear()
        persist()
        return lines.toList()
    }

    @Synchronized
    fun clear(): List<String> {
        lines.clear()
        runCatching { file?.delete() }
        return lines.toList()
    }

    @Synchronized
    fun snapshot(): List<String> = lines.toList()

    @Synchronized
    fun exportText(): String = buildString {
        appendLine("PodFS debug log")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        appendLine()
        lines.forEach { appendLine(it) }
    }

    private fun persist() {
        runCatching { file?.writeText(lines.joinToString("\n", postfix = "\n")) }
    }
}
