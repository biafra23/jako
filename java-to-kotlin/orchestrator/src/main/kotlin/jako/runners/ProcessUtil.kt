package jako.runners

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Shared subprocess wrapper.
 *
 * **Why this exists:** the obvious-looking pattern
 *
 *     val proc = pb.start()
 *     val out = proc.inputStream.bufferedReader().readText()  // blocks until EOF
 *     val err = proc.errorStream.bufferedReader().readText()
 *     val finished = proc.waitFor(timeoutSeconds, SECONDS)
 *
 * defeats the timeout. `readText()` blocks until the child closes stdout —
 * if the child hangs (dead socket, OS not reaping a stuck syscall, etc.),
 * the parent JVM is stuck inside `read`, never reaches `waitFor`, and the
 * timeout machinery never fires. We observed a `claude -p` call sit there
 * for 60+ minutes with the wrapper unable to recover.
 *
 * Instead, redirect both streams to temp files via `ProcessBuilder.Redirect.to`,
 * call `waitFor(timeout)` first, force-kill on expiry, and only then read
 * the files. The parent never blocks on a stream that won't EOF.
 *
 * The two pre-start hooks cover the cases the in-line callers needed:
 *  - `mergeStderr`: collapse stderr into stdout (git wants this).
 *  - `envScrub`:    remove env vars from the child (e.g. ANTHROPIC_API_KEY
 *                   so `claude -p` falls back to its OAuth subscription
 *                   instead of an invalid key from the parent shell).
 *  - `afterStart`:  arbitrary side-effect right after `Process.start()`
 *                   (used to close stdin so `claude -p` doesn't wait 3s
 *                   for stdin data before proceeding).
 */
data class ProcessResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val elapsedSeconds: Double,
)

fun runProcess(
    cmd: List<String>,
    cwd: Path,
    timeoutSeconds: Long,
    mergeStderr: Boolean = false,
    envScrub: List<String> = emptyList(),
    afterStart: ((Process) -> Unit)? = null,
): ProcessResult {
    val outFile = Files.createTempFile("jako-proc-out-", ".log")
    val errFile = if (mergeStderr) outFile else Files.createTempFile("jako-proc-err-", ".log")
    try {
        val pb = ProcessBuilder(cmd)
            .directory(cwd.toFile())
            .redirectOutput(outFile.toFile())
            .redirectError(errFile.toFile())
        if (mergeStderr) pb.redirectErrorStream(true)
        for (key in envScrub) pb.environment().remove(key)

        val t0 = System.currentTimeMillis()
        val proc = pb.start()
        afterStart?.invoke(proc)

        val finished = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        val elapsed = (System.currentTimeMillis() - t0) / 1000.0

        if (!finished) {
            proc.destroyForcibly()
            // Give the OS a brief moment to flush the child's buffers
            // before we read what it managed to write.
            proc.waitFor(5, TimeUnit.SECONDS)
            val out = readOrEmpty(outFile)
            val err = (if (mergeStderr) "" else readOrEmpty(errFile)) +
                "\n[timeout after ${timeoutSeconds}s]"
            return ProcessResult(-1, out, err, elapsed)
        }
        return ProcessResult(
            exitCode = proc.exitValue(),
            stdout = readOrEmpty(outFile),
            stderr = if (mergeStderr) "" else readOrEmpty(errFile),
            elapsedSeconds = elapsed,
        )
    } finally {
        runCatching { Files.deleteIfExists(outFile) }
        if (!mergeStderr) runCatching { Files.deleteIfExists(errFile) }
    }
}

private fun readOrEmpty(p: Path): String = runCatching { Files.readString(p) }.getOrDefault("")
