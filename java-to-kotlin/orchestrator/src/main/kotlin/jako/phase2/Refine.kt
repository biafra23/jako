package jako.phase2

import jako.Config
import jako.runners.SkillResult
import jako.runners.invokeDeepSeek
import jako.runners.invokeLocalLlm
import jako.runners.invokeSkill
import jako.runners.parseWindowResetSeconds
import jako.runners.probeLocalLlm
import java.nio.file.Path

/**
 * Per-file backend chain policy — implements plan §"Per-file backend chain".
 *
 * Step 1 (J2K) lives in phase2.Convert; this module owns steps 2–5:
 *
 *   2. Local LLM      — LOW risk only, if enabled and probe passed
 *   3. Claude         — Haiku / Sonnet / Opus per risk tier
 *   4. DeepSeek v4    — when Claude reports 5-hour window exhausted
 *   5. Wait           — DeepSeek unavailable: sleep until window resets,
 *                       then retry on Claude.
 */

class RefineState(cfg: Config) {
    /** epoch millis; 0 means the claude window is open as far as we know */
    private var claudePausedUntil: Long = 0L

    /** Set once at phase-2 start; only matters when local_model is enabled. */
    val localReachable: Boolean = if (cfg.localModel.enabled) probeLocalLlm(cfg) else false

    fun claudeAvailable(): Boolean = System.currentTimeMillis() >= claudePausedUntil

    fun markClaudePaused(secondsFromNow: Long?) {
        val delta = secondsFromNow ?: 3600L
        val target = System.currentTimeMillis() + delta * 1000
        if (target > claudePausedUntil) claudePausedUntil = target
    }

    fun resumeClaudeIfWindowExpired() {
        if (System.currentTimeMillis() >= claudePausedUntil) claudePausedUntil = 0L
    }
}

fun refine(
    cfg: Config,
    state: RefineState,
    javaFile: Path,
    ktFile: Path,
    risk: String,
    cwd: Path,
    extraUserPrompt: String = "",
): SkillResult {
    val skill = cfg.skillPath(cfg.skills.javaToKotlin)

    // Chain step 2: local LLM for LOW files when enabled and reachable.
    if (risk == "LOW" && cfg.localModel.enabled && state.localReachable) {
        val r = invokeLocalLlm(
            cfg = cfg, skillPath = skill,
            javaFile = javaFile, ktFile = ktFile,
            extraUserPrompt = extraUserPrompt,
        )
        if (r.ok) return r
        // Local failed for this file — fall through to step 3 (Claude Haiku).
    }

    state.resumeClaudeIfWindowExpired()

    // Chain step 3: Claude. If we know the window is closed AND fallback is
    // enabled, skip straight to DeepSeek for this file.
    if (!state.claudeAvailable() && cfg.fallback.enabled) {
        return invokeDeepSeek(
            cfg = cfg, skillPath = skill,
            javaFile = javaFile, ktFile = ktFile,
            extraUserPrompt = extraUserPrompt,
        )
    }

    val claude = invokeSkill(
        cfg = cfg, skillPath = skill,
        javaFile = javaFile, ktFile = ktFile,
        risk = risk, cwd = cwd,
        extraUserPrompt = extraUserPrompt,
        onRateLimit = "report",
    )
    if (claude.ok) return claude

    if (claude.rateLimited) {
        val waitSec = parseWindowResetSeconds(claude.stdoutTail, claude.stderrTail)
        state.markClaudePaused(waitSec)
        // Chain step 4: DeepSeek if enabled.
        if (cfg.fallback.enabled) {
            return invokeDeepSeek(
                cfg = cfg, skillPath = skill,
                javaFile = javaFile, ktFile = ktFile,
                extraUserPrompt = extraUserPrompt,
            )
        }
        // Chain step 5: wait until the window resets, then retry Claude once.
        if (waitSec != null && waitSec > 0) Thread.sleep(minOf(waitSec, 6 * 3600L) * 1000)
        return invokeSkill(
            cfg = cfg, skillPath = skill,
            javaFile = javaFile, ktFile = ktFile,
            risk = risk, cwd = cwd,
            extraUserPrompt = extraUserPrompt,
            onRateLimit = "sleep",
        )
    }

    // Claude failed for a non-rate-limit reason. Don't switch backends — the
    // outer three-strike retry loop in Convert.kt will retry on Claude.
    return claude
}
