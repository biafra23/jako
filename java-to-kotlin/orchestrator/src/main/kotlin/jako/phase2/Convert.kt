package jako.phase2

import jako.AnalysisResult
import jako.Config
import jako.JavaSourceUnit
import jako.RunState
import jako.runners.classifyFailure
import jako.runners.commitFiles
import jako.runners.compileAndTest
import jako.runners.convertJ2K
import jako.runners.describeJ2K
import jako.runners.discardUnstaged
import jako.runners.revertLast
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Phase 2 — file-by-file conversion loop.
 *
 * For each batch in convert-order.json:
 *   1. J2K mechanical pass on every file in the batch.
 *   2. Refinement via the backend chain (phase2.Refine).
 *   3. ./gradlew :module:compileKotlin :module:test (via gradle.test_command).
 *   4. On green: git commit; on red: classify, retry with error context.
 *   5. Three strikes: revert + mark `failed` + move on.
 *
 * Resumable: a unit already at status=committed is skipped unless --force.
 */
data class ConvertReport(
    var converted: Int = 0,
    var skipped: Int = 0,
    var failed: Int = 0,
    var elapsedSeconds: Double = 0.0,
)

private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
private fun ts(): String = "[${LocalTime.now().format(timeFmt)} local]"

private fun ktTargetFor(cfg: Config, unit: JavaSourceUnit): Path {
    val javaPath = Path.of(unit.sourcePath)
    val root = if (cfg.project.module.isNotBlank() && cfg.project.module != ".")
        cfg.projectRoot().resolve(cfg.project.module) else cfg.projectRoot()
    val srcSet = if (unit.isTest) "jvmTest" else "jvmMain"
    val javaRoot = root.resolve("src/$srcSet/java")
    val rel = try {
        javaRoot.relativize(javaPath)
    } catch (_: IllegalArgumentException) {
        Path.of(unit.relativePath)
    }
    val ktRel = rel.toString().removeSuffix(".java") + ".kt"
    return root.resolve("src/$srcSet/kotlin").resolve(ktRel)
}

private fun refineOne(
    cfg: Config,
    refineState: RefineState,
    unit: JavaSourceUnit,
    ktFile: Path,
    extra: String = "",
): Triple<Boolean, String, String> {
    val res = refine(
        cfg = cfg,
        state = refineState,
        javaFile = Path.of(unit.sourcePath),
        ktFile = ktFile,
        risk = unit.risk,
        cwd = cfg.projectRoot(),
        extraUserPrompt = extra,
    )
    val tail = if (res.stderrTail.isNotBlank()) res.stderrTail else res.stdoutTail
    return Triple(res.ok, res.model, tail)
}

private fun attemptGroup(
    cfg: Config,
    units: List<JavaSourceUnit>,
    state: RunState,
    refineState: RefineState,
    log: (String) -> Unit,
): Boolean {
    // Step 1 — J2K mechanical pass.
    // J2K success deletes the original .java (stashed alongside the .kt as
    // a .java.bak). If a previous attempt in this run already did the
    // mechanical pass, the .java is gone — re-running J2K would fail with
    // "no such file". The presence of `<kt>.java.bak` next to the .kt is
    // the canonical signal that J2K already ran for this unit; check it
    // directly rather than relying on state.status (which gets overwritten
    // to "failed" by the refine/verify steps).
    val ktTargets = mutableListOf<Path>()
    for (u in units) {
        val kt = ktTargetFor(cfg, u)
        val javaBak = kt.parent.resolve(kt.fileName.toString() + ".java.bak")
        if (Files.exists(kt) && Files.size(kt) > 0 && Files.exists(javaBak)) {
            log("${ts()} j2k    skipped (already converted): ${u.relativePath}")
            ktTargets.add(kt)
            continue
        }
        log("${ts()} j2k    ${u.relativePath} -> ${kt.relativeToOrSelf(cfg.projectRoot())}")
        val r = convertJ2K(cfg, Path.of(u.sourcePath), kt)
        if (!r.ok) {
            log("${ts()} j2k    FAILED for ${u.relativePath}: ${r.stderrTail.take(200)}")
            state.mark(u.sourcePath, status = "failed", lastError = "j2k: ${r.stderrTail.take(500)}")
            return false
        }
        // Stash the original Java alongside the .kt so manual review is one mv away.
        val javaPath = Path.of(u.sourcePath)
        val backup = kt.parent.resolve(kt.fileName.toString() + ".java.bak")
        Files.writeString(backup, Files.readString(javaPath))
        Files.deleteIfExists(javaPath)
        state.mark(u.sourcePath, status = "j2k_done", ktPath = kt.toString())
        ktTargets.add(kt)
    }
    state.save()

    // Step 2 — refinement via backend chain.
    for ((u, kt) in units.zip(ktTargets)) {
        log("${ts()} refine ${kt.relativeToOrSelf(cfg.projectRoot())} (risk=${u.risk})")
        val (ok, model, tail) = refineOne(cfg, refineState, u, kt)
        state.mark(u.sourcePath, modelUsed = model)
        if (!ok) {
            log("${ts()} refine FAILED for ${u.relativePath}: ${tail.take(200)}")
            state.mark(u.sourcePath, status = "failed", lastError = "refine: ${tail.take(500)}")
            return false
        }
        state.mark(u.sourcePath, status = "refined")
    }
    state.save()

    // Step 3 — gradle compile + test gate.
    log("${ts()} gradle compile + test")
    var g = compileAndTest(cfg)
    if (g.ok) {
        log("${ts()} gradle OK (${"%.1f".format(g.elapsedSeconds)}s)")
        units.forEach { state.mark(it.sourcePath, status = "verified") }
        state.save()
        return true
    }

    val tag = classifyFailure(g)
    log("${ts()} gradle FAILED ($tag, rc=${g.exitCode}, ${"%.1f".format(g.elapsedSeconds)}s)")
    val err = g.outputTail()

    // One auto-retry with the error inlined into the user prompt.
    for ((u, kt) in units.zip(ktTargets)) {
        log("${ts()} refine#2 with compiler-error context for ${u.relativePath}")
        val (ok, model, tail) = refineOne(
            cfg, refineState, u, kt,
            extra = "[$tag] gradle output tail:\n$err",
        )
        state.mark(u.sourcePath, modelUsed = model)
        if (!ok) {
            state.mark(u.sourcePath, status = "failed", lastError = "refine#2: ${tail.take(500)}")
            return false
        }
    }
    g = compileAndTest(cfg)
    if (g.ok) {
        log("${ts()} gradle OK on retry (${"%.1f".format(g.elapsedSeconds)}s)")
        units.forEach { state.mark(it.sourcePath, status = "verified") }
        state.save()
        return true
    }
    log("${ts()} gradle still FAILED after refine#2: ${g.outputTail().take(200)}")
    return false
}

private fun Path.relativeToOrSelf(base: Path): Path = try {
    base.relativize(this)
} catch (_: IllegalArgumentException) {
    this
}

fun runConvert(
    cfg: Config,
    analysis: AnalysisResult,
    state: RunState,
    only: List<String> = emptyList(),
    log: (String) -> Unit = ::println,
): ConvertReport {
    val refineState = RefineState(cfg)
    val fb = if (cfg.fallback.enabled) "deepseek" else "none"
    val local = if (refineState.localReachable) "local-LLM-ok"
        else if (cfg.localModel.enabled) "local-LLM-unreachable" else "local-LLM-off"
    log("${ts()} phase 2 starting | ${describeJ2K(cfg)} | $local | fallback=$fb")

    val unitsByPath = analysis.units.associateBy { it.sourcePath }
    val onlySet: Set<String>? = if (only.isEmpty()) null
        else only.map { Path.of(it).toAbsolutePath().normalize().toString() }.toSet()

    val report = ConvertReport()
    val t0 = System.currentTimeMillis()

    for (batch in analysis.order) {
        val group = batch.mapNotNull { unitsByPath[it] }
            .let { g -> if (onlySet == null) g else g.filter { it.sourcePath in onlySet } }
        if (group.isEmpty()) continue

        val allDone = group.all { state.getOrCreate(it.sourcePath).status in setOf("committed", "verified") }
        if (allDone) {
            log("${ts()} SKIP ${group.joinToString(", ") { it.relativePath }} (already done)")
            report.skipped += group.size
            continue
        }

        val isCycle = group.size > 1
        if (isCycle) {
            log("${ts()} CYCLE batch (${group.size} files): ${group.joinToString(", ") { it.relativePath }}")
        }

        var green = false
        for (attempt in 1..cfg.verify.maxRetries) {
            if (attempt > 1) log("${ts()} retry $attempt/${cfg.verify.maxRetries}")
            group.forEach { state.mark(it.sourcePath, incrementRetry = (attempt > 1)) }
            green = attemptGroup(cfg, group, state, refineState, log)
            if (green) break
        }

        if (green) {
            val pathsToAdd = mutableListOf<Path>()
            for (u in group) {
                val kt = ktTargetFor(cfg, u)
                pathsToAdd.add(kt)
                pathsToAdd.add(Path.of(u.sourcePath))  // captures the .java deletion
                pathsToAdd.add(kt.parent.resolve(kt.fileName.toString() + ".java.bak"))
            }
            val msg = ("convert: " + group.joinToString(", ") { it.relativePath }).take(72)
            val cr = commitFiles(cfg, pathsToAdd, msg)
            log("${ts()} git: ${cr.message}")
            group.forEach { state.mark(it.sourcePath, status = "committed") }
            report.converted += group.size
        } else {
            log("${ts()} three strikes — reverting and marking manual-review")
            revertLast(cfg)
            val toRestore = group.map { Path.of(it.sourcePath) } + group.map { ktTargetFor(cfg, it) }
            discardUnstaged(cfg, toRestore)
            group.forEach { state.mark(it.sourcePath, status = "failed", addNote = "manual-review") }
            report.failed += group.size
        }
        state.save()
    }

    report.elapsedSeconds = (System.currentTimeMillis() - t0) / 1000.0
    log("${ts()} phase 2 done: converted=${report.converted} skipped=${report.skipped} " +
        "failed=${report.failed} elapsed=${"%.1f".format(report.elapsedSeconds)}s")
    return report
}
