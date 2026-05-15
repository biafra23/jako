package jako.runners

import jako.Config
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Gradle compile + test gate — phase 2.3.
 *
 * The orchestrator only cares: did `./gradlew :module:compileKotlin
 * :module:test` exit 0 (configurable via `gradle.test_command`)? Failure
 * classification is shallow — we look for a handful of patterns to route
 * between deterministic fixes and LLM retries.
 */
data class GradleResult(
    val ok: Boolean,
    val cmd: List<String>,
    val elapsedSeconds: Double,
    val exitCode: Int,
    val stdoutTail: String,
    val stderrTail: String,
) {
    fun outputTail(n: Int = 4000): String =
        (stdoutTail + "\n" + stderrTail).trim().takeLast(n)
}

private fun resolveWrapper(cfg: Config): Path {
    val raw = cfg.gradle.wrapper
    val p = Path.of(raw)
    return if (p.isAbsolute) p else cfg.projectRoot().resolve(raw).toAbsolutePath().normalize()
}

private fun shellSplit(s: String): List<String> {
    // Minimal split — these task strings don't have quoted spaces in practice.
    return s.split(Regex("\\s+")).filter { it.isNotBlank() }
}

fun compileAndTest(cfg: Config): GradleResult {
    val wrapper = resolveWrapper(cfg)
    if (!Files.exists(wrapper)) error("gradle wrapper not found at $wrapper")

    val taskStr = cfg.gradle.testCommand.replace("{module}", cfg.project.module)
    val tasks = shellSplit(taskStr)
    val cmd = listOf(wrapper.toString()) + tasks

    val t0 = System.currentTimeMillis()
    val pb = ProcessBuilder(cmd).directory(cfg.projectRoot().toFile()).redirectErrorStream(false)
    val proc = pb.start()
    val out = proc.inputStream.bufferedReader().readText()
    val err = proc.errorStream.bufferedReader().readText()
    val finished = proc.waitFor(cfg.gradle.timeoutSeconds, TimeUnit.SECONDS)
    val elapsed = (System.currentTimeMillis() - t0) / 1000.0
    if (!finished) {
        proc.destroyForcibly()
        return GradleResult(
            ok = false, cmd = cmd, elapsedSeconds = elapsed, exitCode = -1,
            stdoutTail = out.takeLast(2000),
            stderrTail = "gradle timed out after ${cfg.gradle.timeoutSeconds}s",
        )
    }
    return GradleResult(
        ok = proc.exitValue() == 0,
        cmd = cmd,
        elapsedSeconds = elapsed,
        exitCode = proc.exitValue(),
        stdoutTail = out.takeLast(4000),
        stderrTail = err.takeLast(4000),
    )
}

/**
 * Tag a gradle failure so phase 2 can pick a remedy.
 *
 *   build_env       — environmental / build-script issue outside the LLM's
 *                     domain (parent project policy task throws at config
 *                     time, duplicate-class collision from a leftover .java,
 *                     NOTICE/license guards, missing wrapper, etc.). Refine
 *                     can't fix these; retrying is just burning cost.
 *   missing_import  — "unresolved reference"; often fixed by adding an import.
 *   syntax          — Kotlin parser choked on the file (refinement bug).
 *   jvm_interop     — Java test can't see the new Kotlin symbol (need @JvmStatic).
 *   test_failure    — compile passed, test asserted false.
 *   unknown         — everything else; retry refinement with the error inlined.
 *
 * Order matters: `build_env` is checked first because its signatures (e.g.
 * "A problem occurred evaluating root project") would otherwise fall through
 * to `unknown` and trigger a pointless refine retry.
 */
fun classifyFailure(result: GradleResult): String {
    val blob = (result.stdoutTail + "\n" + result.stderrTail).lowercase()
    val buildEnvSignals = listOf(
        "a problem occurred evaluating",         // config-time eval failures
        "a problem occurred configuring",
        "could not create task",                 // task registration threw
        "is a duplicate but no duplicate handling", // duplicate jar entry
        "plugin .* not found",
        "could not resolve all dependencies",    // network / repo issue
        "no notice file",                        // Tuweni :checkNotice flavor
        "notice file is not up-to-date",
        "license header",                        // spotless license-header guard
    )
    if (buildEnvSignals.any { it.toRegex().containsMatchIn(blob) }) return "build_env"
    return when {
        "unresolved reference" in blob -> "missing_import"
        "expecting an expression" in blob || "syntax error" in blob -> "syntax"
        "cannot find symbol" in blob && "java" in blob && "kotlin" in blob -> "jvm_interop"
        "task :test failed" in blob || "tests failed" in blob -> "test_failure"
        else -> "unknown"
    }
}
