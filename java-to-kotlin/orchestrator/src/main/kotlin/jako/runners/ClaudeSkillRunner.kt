package jako.runners

import jako.Config
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.nio.file.Files
import java.nio.file.Path
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Result type shared across all refinement backends (claude / local / deepseek).
 * Each backend writes the .kt file itself on success — the caller only inspects
 * `ok` and (for chain routing) `rateLimited`.
 */
data class SkillResult(
    val ok: Boolean,
    val model: String,
    val elapsedSeconds: Double,
    val stdoutTail: String = "",
    val stderrTail: String = "",
    val rateLimited: Boolean = false,
    val jsonPayload: JsonElement? = null,
)

/**
 * `claude -p` invocation with the JetBrains skill appended via
 * --append-system-prompt-file. The orchestrator never writes Java→Kotlin
 * prompts of its own — they live in the skill markdown.
 */

private val rateLimitPattern = Regex(
    """rate.?limit|5.?hour|usage.?limit|quota.?exhaust|try again at""",
    RegexOption.IGNORE_CASE,
)

private val tryAgainAtPattern = Regex(
    """try again at\s+(\d{1,2}):(\d{2})""",
    RegexOption.IGNORE_CASE,
)

private fun resolveCli(cfg: Config): String {
    if (cfg.claude.cli.isNotBlank()) return cfg.claude.cli
    val path = System.getenv("PATH").orEmpty().split(":")
    for (dir in path) {
        val candidate = Path.of(dir, "claude")
        if (Files.isExecutable(candidate)) return candidate.toString()
    }
    error("`claude` CLI not found on PATH and claude.cli is empty. " +
        "Install Claude Code (https://claude.com/code) or set claude.cli.")
}

private fun modelForRisk(cfg: Config, risk: String): String =
    cfg.claude.models[risk] ?: cfg.claude.defaultModel

private fun buildUserPrompt(javaFile: Path, ktFile: Path, extra: String): String {
    val lines = mutableListOf(
        "Refine the Kotlin file at $ktFile.",
        "The original Java is at $javaFile for reference.",
        "Constraints:",
        "  - Public API must remain Java-callable; Java tests in src/jvmTest/java compile against this file.",
        "  - Add @JvmStatic / @JvmField / @JvmOverloads / @JvmName as needed for interop.",
        "  - Do not introduce new external dependencies.",
        "Apply the JetBrains java-to-kotlin skill conventions.",
        "Do not deliberate or print reasoning — edit the file and stop.",
    )
    if (extra.isNotBlank()) {
        lines.add("")
        lines.add("Additional context from the previous attempt:")
        lines.add(extra)
    }
    return lines.joinToString("\n")
}

private fun looksRateLimited(stdout: String, stderr: String): Boolean =
    rateLimitPattern.containsMatchIn(stdout) || rateLimitPattern.containsMatchIn(stderr)

/**
 * Best-effort: how long until the 5-hour window resets, in seconds.
 * Returns null if no clock hint was found. Used by the refinement
 * policy to schedule a wake-up.
 */
fun parseWindowResetSeconds(stdout: String, stderr: String): Long? {
    val combined = "$stdout\n$stderr"
    val m = tryAgainAtPattern.find(combined) ?: return null
    val targetH = m.groupValues[1].toInt()
    val targetM = m.groupValues[2].toInt()
    val now = Calendar.getInstance()
    val target = (now.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, targetH)
        set(Calendar.MINUTE, targetM)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    var delta = (target.timeInMillis - now.timeInMillis) / 1000
    if (delta < 0) delta += 24 * 3600
    return minOf(delta, 6 * 3600L)
}

private fun sleepUntilWindowReset(stdout: String, stderr: String) {
    val seconds = parseWindowResetSeconds(stdout, stderr) ?: 300L
    Thread.sleep(seconds * 1000)
}

private fun runProcess(cmd: List<String>, cwd: Path, timeoutSeconds: Long): ProcessResult {
    val pb = ProcessBuilder(cmd).directory(cwd.toFile()).redirectErrorStream(false)
    // The plan-2 design uses the Claude Code subscription (5-hour window,
    // OAuth-based) for `claude -p`, with DeepSeek as the rate-limit fallback.
    // If ANTHROPIC_API_KEY is present in the parent env (set, say, by the
    // CLI session itself), claude -p prefers it over the subscription token
    // and returns HTTP 401 if it's not a valid paid API key. Strip it from
    // the subprocess env so the OAuth subscription path always wins.
    pb.environment().remove("ANTHROPIC_API_KEY")
    val t0 = System.currentTimeMillis()
    val proc = pb.start()
    // Newer claude -p versions block ~3s waiting for stdin even when the
    // prompt is a positional arg, then emit "no stdin data received in 3s"
    // and (in some versions) exit non-zero. Close stdin so claude proceeds
    // immediately and the warning never appears.
    proc.outputStream.close()
    val out = proc.inputStream.bufferedReader().readText()
    val err = proc.errorStream.bufferedReader().readText()
    val finished = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)
    val elapsed = (System.currentTimeMillis() - t0) / 1000.0
    if (!finished) {
        proc.destroyForcibly()
        return ProcessResult(-1, out, err + "\n[timeout after ${timeoutSeconds}s]", elapsed)
    }
    return ProcessResult(proc.exitValue(), out, err, elapsed)
}

internal data class ProcessResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val elapsedSeconds: Double,
)

/**
 * Invoke claude -p with the JetBrains skill.
 *
 * `onRateLimit` controls policy when the 5-hour cap is hit:
 *   "report" - return immediately with rateLimited=true (caller handles fallback)
 *   "sleep"  - block here until the window resets, then retry once
 */
fun invokeSkill(
    cfg: Config,
    skillPath: Path,
    javaFile: Path,
    ktFile: Path,
    risk: String,
    cwd: Path,
    extraUserPrompt: String = "",
    onRateLimit: String = "report",
): SkillResult {
    if (!Files.exists(skillPath)) {
        error("skill file not found at $skillPath. Vendor Kotlin/kotlin-agent-skills " +
            "(git submodule under ${cfg.skills.root}).")
    }
    val cli = resolveCli(cfg)
    val model = modelForRisk(cfg, risk)
    val userPrompt = buildUserPrompt(javaFile, ktFile, extraUserPrompt)

    val cmd = buildList {
        add(cli); add("-p")
        add("--model"); add(model)
        add("--output-format"); add("json")
        add("--max-turns"); add(cfg.claude.maxTurns.toString())
        add("--permission-mode"); add(cfg.claude.permissionMode)
        add("--append-system-prompt-file"); add(skillPath.toString())
        addAll(cfg.claude.extraArgs)
        add(userPrompt)
    }

    var pr = runProcess(cmd, cwd, cfg.gradle.timeoutSeconds)
    var rateLimited = looksRateLimited(pr.stdout, pr.stderr)
    if (rateLimited && onRateLimit == "sleep") {
        sleepUntilWindowReset(pr.stdout, pr.stderr)
        pr = runProcess(cmd, cwd, cfg.gradle.timeoutSeconds)
        rateLimited = looksRateLimited(pr.stdout, pr.stderr)
    }

    val payload = runCatching { Json.parseToJsonElement(pr.stdout) }.getOrNull()
    val ktWritten = Files.exists(ktFile) && Files.size(ktFile) > 0
    // claude -p exits non-zero on `error_max_turns` (the turn budget ran
    // out mid-conversation) but the model has often already produced the
    // .kt before then — it just kept burning turns on follow-up tool calls
    // (read-back verification, an extraneous gradle invocation, etc.).
    // Accept that outcome iff the .kt was actually written; the gradle
    // gate downstream is the real verdict on whether the file is good.
    // Substring match on the small single-object JSON stdout is enough.
    val exhaustedTurnsWithFile = ktWritten && "error_max_turns" in pr.stdout
    val ok = (pr.exitCode == 0 || exhaustedTurnsWithFile) && ktWritten && !rateLimited

    return SkillResult(
        ok = ok,
        model = model,
        elapsedSeconds = pr.elapsedSeconds,
        stdoutTail = pr.stdout.takeLast(2000),
        stderrTail = pr.stderr.takeLast(2000),
        rateLimited = rateLimited,
        jsonPayload = payload,
    )
}

/**
 * One-shot variant for phase 1 (AGP9 migration). No fallback path — sleeps
 * through a rate-limit since it's one call total, not 49.
 */
fun invokeSkillOneShot(
    cfg: Config,
    skillPath: Path,
    userPrompt: String,
    cwd: Path,
    model: String? = null,
): SkillResult {
    val cli = resolveCli(cfg)
    val resolvedModel = model ?: cfg.claude.defaultModel
    val cmd = buildList {
        add(cli); add("-p")
        add("--model"); add(resolvedModel)
        add("--output-format"); add("json")
        add("--permission-mode"); add(cfg.claude.permissionMode)
        add("--append-system-prompt-file"); add(skillPath.toString())
        addAll(cfg.claude.extraArgs)
        add(userPrompt)
    }
    var pr = runProcess(cmd, cwd, cfg.gradle.timeoutSeconds)
    var rateLimited = looksRateLimited(pr.stdout, pr.stderr)
    if (rateLimited && cfg.claude.pauseOnRateLimit) {
        sleepUntilWindowReset(pr.stdout, pr.stderr)
        pr = runProcess(cmd, cwd, cfg.gradle.timeoutSeconds)
        rateLimited = looksRateLimited(pr.stdout, pr.stderr)
    }
    return SkillResult(
        ok = pr.exitCode == 0 && !rateLimited,
        model = resolvedModel,
        elapsedSeconds = pr.elapsedSeconds,
        stdoutTail = pr.stdout.takeLast(2000),
        stderrTail = pr.stderr.takeLast(2000),
        rateLimited = rateLimited,
    )
}
