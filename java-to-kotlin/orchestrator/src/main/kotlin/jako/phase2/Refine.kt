package jako.phase2

import jako.Config
import jako.runners.SkillResult
import jako.runners.invokeDeepSeek
import jako.runners.invokeLocalLlm
import jako.runners.invokeSkill
import jako.runners.parseWindowResetSeconds
import jako.runners.probeLocalLlm
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong

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
    /**
     * epoch millis; 0 means the claude window is open as far as we know.
     * Atomic so it's safe to read/write from parallel `refine(...)` calls
     * fanned out by the convert loop. `updateAndGet` is the right primitive
     * for the read-compare-write inside `markClaudePaused`.
     */
    private val claudePausedUntil = AtomicLong(0L)

    /** Set once at phase-2 start; only matters when local_model is enabled. */
    val localReachable: Boolean = if (cfg.localModel.enabled) probeLocalLlm(cfg) else false

    fun claudeAvailable(): Boolean = System.currentTimeMillis() >= claudePausedUntil.get()

    fun markClaudePaused(secondsFromNow: Long?) {
        val delta = secondsFromNow ?: 3600L
        val target = System.currentTimeMillis() + delta * 1000
        claudePausedUntil.updateAndGet { current -> maxOf(current, target) }
    }

    fun resumeClaudeIfWindowExpired() {
        val now = System.currentTimeMillis()
        claudePausedUntil.updateAndGet { current -> if (now >= current) 0L else current }
    }
}

fun refine(
    cfg: Config,
    state: RefineState,
    javaFile: Path,
    ktFile: Path,
    risk: String,
    cwd: Path,
    isTest: Boolean = false,
    extraUserPrompt: String = "",
): SkillResult {
    val skill = cfg.skillPath(cfg.skills.javaToKotlin)

    // Chain step 2: local LLM for LOW files when enabled and reachable.
    if (risk == "LOW" && cfg.localModel.enabled && state.localReachable) {
        val r = invokeLocalLlm(
            cfg = cfg, skillPath = skill,
            javaFile = javaFile, ktFile = ktFile,
            isTest = isTest,
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
            isTest = isTest,
            extraUserPrompt = extraUserPrompt,
        )
    }

    val claude = invokeSkill(
        cfg = cfg, skillPath = skill,
        javaFile = javaFile, ktFile = ktFile,
        risk = risk, cwd = cwd,
        isTest = isTest,
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
                isTest = isTest,
                extraUserPrompt = extraUserPrompt,
            )
        }
        // Chain step 5: wait until the window resets, then retry Claude once.
        if (waitSec != null && waitSec > 0) Thread.sleep(minOf(waitSec, 6 * 3600L) * 1000)
        return invokeSkill(
            cfg = cfg, skillPath = skill,
            javaFile = javaFile, ktFile = ktFile,
            risk = risk, cwd = cwd,
            isTest = isTest,
            extraUserPrompt = extraUserPrompt,
            onRateLimit = "sleep",
        )
    }

    // Claude failed for a non-rate-limit reason. Don't switch backends — the
    // outer three-strike retry loop in Convert.kt will retry on Claude.
    return claude
}
