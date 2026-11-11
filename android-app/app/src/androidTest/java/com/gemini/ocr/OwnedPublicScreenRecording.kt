package com.gemini.ocr

import android.app.UiAutomation
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.io.File

/** Owns only the screenrecord process launched for this explicitly authorized public-video run. */
internal class OwnedPublicScreenRecording private constructor(
    private val automation: UiAutomation, private val pid: Int, private val path: String,
) {
    suspend fun stop() {
        val command = readBounded(automation, "cat /proc/$pid/comm").trim()
        if (command.isEmpty()) return // Its bounded time limit already finished.
        check(command == "screenrecord") { "Recording PID no longer belongs to screenrecord" }
        val arguments = readBounded(automation, "cat /proc/$pid/cmdline").split('\u0000').filter { it.isNotEmpty() }
        check(owns(command, arguments, path)) { "Refuse to stop a different recording" }
        readBounded(automation, "kill -2 $pid") // SIGINT lets screenrecord finalize the MP4.
        withTimeout(5000) {
            while (readBounded(automation, "cat /proc/$pid/comm").trim() == "screenrecord") {
                delay(50)
            }
        }
    }

    companion object {
        internal fun parsePid(value: String): Int {
            require(value.trim().matches(Regex("[1-9][0-9]{0,7}")))
            return value.trim().toInt().also { require(it in 1..4194304) }
        }
        internal fun owns(command: String, arguments: List<String>, path: String): Boolean =
            command == "screenrecord" && arguments.firstOrNull()?.substringAfterLast('/') == "screenrecord" &&
                arguments.lastOrNull() == path

        internal fun launchCommand(file: File, seconds: Int): String {
            require(seconds in 1..95)
            val path = file.absolutePath
            require(path.matches(Regex("[/A-Za-z0-9._-]{1,240}")))
            require(path.contains("/com.gemini.ocr.codexbench/files/verification/") && path.endsWith(".mp4"))
            // UiAutomation uses Runtime.exec(String), which does not preserve quoted
            // sh -c arguments. Pass a generated file to sh as one whitespace-free path.
            return "screenrecord --time-limit $seconds --size 1280x720 --bit-rate 2500000 $path >/dev/null 2>&1 &\necho ${'$'}!\n"
        }
        fun start(automation: UiAutomation, file: File, seconds: Int): OwnedPublicScreenRecording {
            check(!file.exists()) { "Preserve previous public recording" }
            val body = launchCommand(file, seconds)
            val script = File.createTempFile(".codex-screenrecord-", ".sh", file.parentFile)
            try {
                script.writeText(body, Charsets.UTF_8)
                val pid = parsePid(readBounded(automation, "sh ${script.absolutePath}"))
                return OwnedPublicScreenRecording(automation, pid, file.absolutePath)
            } finally { script.delete() } // Only this newly created helper file is removed.
        }
        private fun readBounded(automation: UiAutomation, command: String): String =
            ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { input ->
                val bytes = ByteArray(4097); var total = 0
                while (total < bytes.size) {
                    val count = input.read(bytes, total, bytes.size - total)
                    if (count < 0) break
                    total += count
                }
                require(total <= 4096) { "Recording helper output exceeded its bound" }
                bytes.copyOf(total).toString(Charsets.UTF_8)
            }
    }
}
