package io.github.adamarmistead.setenv.internal

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal object CommandExecutor {

    /**
     * Shell metacharacters that could enable command injection.
     */
    private val DANGEROUS_CHARS = charArrayOf(';', '&', '|', '`', '$', '>', '<', '\n', '\r', '"', '\'')

    fun execute(command: String, timeoutSeconds: Int, writeOutput: Boolean): String {
        val dangerous = DANGEROUS_CHARS.firstOrNull { command.contains(it) }
        if (dangerous != null) {
            throw RuntimeException(
                "Command contains potentially dangerous character '$dangerous': $command",
            )
        }

        val processBuilder = if (System.getProperty("os.name").contains("Windows", ignoreCase = true)) {
            ProcessBuilder("cmd", "/c", command)
        } else {
            ProcessBuilder("sh", "-c", command)
        }

        processBuilder.redirectErrorStream(true)

        val process = processBuilder.start()

        // Drain stdout on a dedicated thread so the main thread is free to enforce
        // the timeout. Reading inline (readText()) would block until EOF and make
        // the waitFor(timeout) below unreachable for any command that hangs.
        val outputRef = AtomicReference<String>("")
        val readerThread = Thread {
            try {
                outputRef.set(process.inputStream.bufferedReader().use { it.readText() })
            } catch (e: Exception) {
                // Stream closed early (e.g. process destroyed on timeout); keep whatever we have.
            }
        }.apply { isDaemon = true; start() }

        val finished = process.waitFor(timeoutSeconds.toLong(), TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            readerThread.join(1_000)
            throw RuntimeException("Command timed out after ${timeoutSeconds}s: $command")
        }

        // Give the reader a moment to flush any trailing output before we read it.
        readerThread.join(1_000)
        val output = outputRef.get()

        if (writeOutput && output.isNotEmpty()) {
            print(output)
        }

        val exitValue = process.exitValue()
        if (exitValue != 0) {
            val details = output.ifBlank { "No output captured." }
            throw RuntimeException("Error executing command (exit code $exitValue):\n$details")
        }

        return output
    }
}
