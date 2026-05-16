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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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

/**
 * One refine outcome surfaced from a parallel batch. Kept separate from
 * `state.mark(...)` calls so all state mutations happen sequentially on
 * the main thread after `awaitAll()` — `RunState` is not thread-safe.
 */
private data class RefineOutcome(
    val unit: JavaSourceUnit,
    val kt: Path,
    val ok: Boolean,
    val model: String,
    val tail: String,
)

/**
 * Mirror of the chain-step ordering inside `refine(...)`, used only to
 * label the model in a failure outcome when an exception bubbled out
 * before any backend ran. Keeps RunState honest about *intent* rather
 * than tagging the unit with the sentinel "?" model.
 */
private fun intendedModelFor(cfg: Config, refineState: RefineState, risk: String): String =
    when {
        risk == "LOW" && cfg.localModel.enabled && refineState.localReachable ->
            "local:${cfg.localModel.model}"
        !refineState.claudeAvailable() && cfg.fallback.enabled ->
            "deepseek:${cfg.fallback.deepseek.model}"
        else -> cfg.claude.models[risk] ?: cfg.claude.defaultModel
    }

/**
 * Fan out one refine call per item with bounded concurrency. Logs are
 * `println` underneath (thread-safe). Catches exceptions and surfaces them
 * as `ok=false` outcomes so a single backend hiccup doesn't poison the
 * whole batch.
 */
private fun parallelRefine(
    cfg: Config,
    refineState: RefineState,
    items: List<Triple<JavaSourceUnit, Path, String>>,
    log: (String) -> Unit,
): List<RefineOutcome> {
    if (items.isEmpty()) return emptyList()
    val concurrency = maxOf(1, cfg.claude.concurrency)
    return runBlocking {
        val sem = Semaphore(concurrency)
        coroutineScope {
            items.map { (u, kt, extra) ->
                async(Dispatchers.IO) {
                    sem.withPermit {
                        log("${ts()} refine ${kt.relativeToOrSelf(cfg.projectRoot())} (risk=${u.risk})")
                        val started = System.currentTimeMillis()
                        // Heartbeat so the per-file silence (a single refine can run
                        // 1-3 min on Haiku) doesn't look like a hang. Tells the user
                        // "still working" + elapsed seconds, every HEARTBEAT_INTERVAL_MS.
                        // The launch lives inside the async's CoroutineScope; the
                        // finally guarantees cancellation so async can complete.
                        val heartbeat = launch {
                            while (isActive) {
                                delay(HEARTBEAT_INTERVAL_MS)
                                val elapsed = (System.currentTimeMillis() - started) / 1000
                                log("${ts()}   ↳ ${kt.relativeToOrSelf(cfg.projectRoot())} — refining for ${elapsed}s")
                            }
                        }
                        try {
                            runCatching { refineOne(cfg, refineState, u, kt, extra) }
                                .fold(
                                    onSuccess = { (ok, model, tail) ->
                                        RefineOutcome(u, kt, ok, model, tail)
                                    },
                                    onFailure = { e ->
                                        // Compute the model we would have picked
                                        // had the call reached a backend, so RunState
                                        // shows an accurate intended-model name
                                        // rather than the misleading "?" sentinel.
                                        val intendedModel = intendedModelFor(cfg, refineState, u.risk)
                                        RefineOutcome(u, kt, false, intendedModel, "exception: ${e.message ?: e.javaClass.simpleName}")
                                    },
                                )
                        } finally {
                            heartbeat.cancel()
                        }
                    }
                }
            }.awaitAll()
        }
    }
}

private const val HEARTBEAT_INTERVAL_MS: Long = 30_000

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
        isTest = unit.isTest,
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
    // "no such file". The canonical "J2K already ran for THIS unit" signal
    // is the conjunction of three artifacts:
    //   - the .kt exists and is non-empty,
    //   - the matching `<kt>.java.bak` exists (J2K wrote it),
    //   - the original .java at `u.sourcePath` is gone (J2K removed it).
    // We check all three together rather than relying on state.status
    // (which gets overwritten to "failed" by the refine/verify steps) or
    // .bak alone (which could be a stale leftover from a rename / earlier
    // workspace state with no matching current J2K run).
    val ktTargets = mutableListOf<Path>()
    for (u in units) {
        val kt = ktTargetFor(cfg, u)
        val javaBak = kt.parent.resolve(kt.fileName.toString() + ".java.bak")
        val originalJava = Path.of(u.sourcePath)
        val j2kAlreadyDone =
            Files.exists(kt) && Files.size(kt) > 0 &&
            Files.exists(javaBak) &&
            !Files.exists(originalJava)
        if (j2kAlreadyDone) {
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

    // Step 2 — refinement via backend chain, fanned out across the batch
    // up to `claude.concurrency` parallel claude/local/deepseek calls.
    // For a 6-file SCC at concurrency=4 this drops a 6-minute serial refine
    // pass to roughly 1.5 minutes. All state mutations stay on the calling
    // thread (RunState is not thread-safe).
    val refineItems = units.zip(ktTargets).map { (u, kt) -> Triple(u, kt, "") }
    val outcomes = parallelRefine(cfg, refineState, refineItems, log)
    var anyFailed = false
    for (o in outcomes) {
        state.mark(o.unit.sourcePath, modelUsed = o.model)
        if (!o.ok) {
            log("${ts()} refine FAILED for ${o.unit.relativePath}: ${o.tail.take(200)}")
            state.mark(o.unit.sourcePath, status = "failed", lastError = "refine: ${o.tail.take(500)}")
            anyFailed = true
        } else {
            state.mark(o.unit.sourcePath, status = "refined")
        }
    }
    state.save()
    if (anyFailed) return false

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

    // Environmental failures (parent-project task policy, duplicate-class
    // collisions from leftover .java, license headers, etc.) aren't anything
    // the LLM can fix by re-refining. Surface them and let the outer retry
    // budget / three-strikes path handle escalation. The user almost always
    // needs to fix something in the target project, not the converted file.
    if (tag == "build_env") {
        log("${ts()} build-env failure — skipping refine#2 (not the LLM's bug)")
        units.forEach {
            state.mark(it.sourcePath, status = "failed", lastError = "[$tag] ${err.take(500)}")
        }
        return false
    }

    // Compile errors are usually surfaced per-file. Re-refining files that
    // already compiled is wasted cost. Parse the kotlin compiler output to
    // find which .kt files the compiler complained about, then refine#2
    // only those, passing each unit its own file-specific error tail
    // instead of the whole batch's. Falls back to re-refining everything
    // when (a) the parser found nothing — test failure, unknown plugin
    // output — OR (b) the parser found files but none match our batch
    // (errors in generated sources, downstream modules, or a cousin
    // package). Both cases would otherwise hand `parallelRefine` an empty
    // list and burn the retry without invoking the LLM.
    val perFileErrors: Map<Path, List<String>> = jako.runners.parsePerFileKotlinErrors(g)
    val erroringKts: Set<Path> = perFileErrors.keys.map { it.toAbsolutePath().normalize() }.toSet()
    val matched: List<Triple<JavaSourceUnit, Path, String>> = units.zip(ktTargets).mapNotNull { (u, kt) ->
        val ktAbs = kt.toAbsolutePath().normalize()
        if (ktAbs !in erroringKts) return@mapNotNull null
        val diagBlock = perFileErrors[ktAbs]?.joinToString("\n\n") ?: ""
        Triple(u, kt, "[$tag] kotlin diagnostics for this file:\n$diagBlock")
    }
    val refine2Items: List<Triple<JavaSourceUnit, Path, String>> = when {
        erroringKts.isEmpty() -> {
            log("${ts()} refine#2: no per-file errors parsed — re-refining all ${units.size} units with batch error tail")
            units.zip(ktTargets).map { (u, kt) -> Triple(u, kt, "[$tag] gradle output tail:\n$err") }
        }
        matched.isEmpty() -> {
            log(
                "${ts()} refine#2: parser found ${erroringKts.size} erroring file(s) but none in this batch " +
                    "(likely generated sources or a downstream module); re-refining all ${units.size} with batch tail",
            )
            units.zip(ktTargets).map { (u, kt) -> Triple(u, kt, "[$tag] gradle output tail:\n$err") }
        }
        else -> {
            log(
                "${ts()} refine#2: ${matched.size}/${units.size} units have compiler diagnostics " +
                    "(others compiled OK — skipping them this pass)",
            )
            matched
        }
    }
    val refine2Outcomes = parallelRefine(cfg, refineState, refine2Items, log)
    for (o in refine2Outcomes) {
        state.mark(o.unit.sourcePath, modelUsed = o.model)
        if (!o.ok) {
            state.mark(o.unit.sourcePath, status = "failed", lastError = "refine#2: ${o.tail.take(500)}")
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
            // Three strikes. Don't call revertLast here — `commitFiles` only
            // runs in the green branch above, so by definition no commit was
            // made for this group. revertLast does `git reset --hard HEAD~1`,
            // which would destroy a completely unrelated commit (potentially
            // the user's own work) when our retry budget is exhausted.
            // discardUnstaged is the right scope: it only touches the paths
            // we know we modified.
            log("${ts()} three strikes — marking manual-review (leaving .kt on disk for inspection)")
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
