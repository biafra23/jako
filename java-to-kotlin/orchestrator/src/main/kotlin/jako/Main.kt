package jako

import jako.phase0.runAnalyze
import jako.phase1.runScaffold
import jako.phase2.runConvert
import jako.runners.ensureWorktree
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * CLI dispatch — see `plan-2-thin-orchestrator.md`.
 *
 * Usage:
 *   ./gradlew :orchestrator:run --args="--phase analyze"
 *   ./gradlew :orchestrator:run --args="--phase scaffold"
 *   ./gradlew :orchestrator:run --args="--phase convert"
 *   ./gradlew :orchestrator:run --args="--phase report"
 *   ./gradlew :orchestrator:run --args="--phase convert --only PATH PATH"
 *   ./gradlew :orchestrator:run --args="--force"
 */
fun main(rawArgs: Array<String>) {
    val args = parseArgs(rawArgs)

    val cfgPath = Path.of(args.config).toAbsolutePath().normalize()
    if (!Files.exists(cfgPath)) {
        System.err.println("config not found: $cfgPath")
        exitProcess(2)
    }
    // Layering: config-file values, then --project / --module overrides, then
    // (optional) --worktree mapping. Each step preserves `Config.base` (the
    // config-file directory used for relative-path resolution); without that,
    // skills/scripts/vendored-skills paths in config.yaml would silently
    // start resolving against the JVM's CWD the moment any CLI override is
    // passed.
    val cfg = loadConfig(cfgPath).withOverrides(args).let { afterOverrides ->
        if (args.worktree.isNullOrBlank()) afterOverrides
        else {
            val sourceRepo = afterOverrides.projectRoot()
            // Run through Config.resolve so the same `~`-expansion and
            // base-relative rules that apply to config.yaml paths also
            // apply here — a bare `Path.of("~/foo").toAbsolutePath()`
            // produces literal `<cwd>/~/foo` because Java doesn't expand `~`.
            val wtPath = afterOverrides.resolve(args.worktree)
            val module = afterOverrides.project.module.ifBlank { "all" }
            val branch = args.worktreeBranch?.ifBlank { null } ?: "jako/$module"
            val effective = ensureWorktree(sourceRepo, wtPath, branch)
            System.err.println("[worktree] $sourceRepo  ->  $effective  (branch: $branch)")
            // Rebind both project.root AND state.dir so per-worktree runs
            // don't share state. `effective.resolve(base.state.dir)` is a
            // no-op when state.dir is absolute (user-set absolute paths
            // are preserved as-is) and pins a relative state.dir inside
            // the worktree (the common case).
            afterOverrides.copy(
                project = afterOverrides.project.copy(root = effective.toString()),
                state = afterOverrides.state.copy(
                    dir = effective.resolve(afterOverrides.state.dir).toString(),
                ),
            ).apply { base = afterOverrides.base }
        }
    }

    val t0 = System.currentTimeMillis()
    when (args.phase) {
        "analyze" -> runAnalyze(cfg)
        "scaffold" -> runScaffold(cfg, loadOrAnalyze(cfg, force = args.force))
        "convert" -> {
            val analysis = loadOrAnalyze(cfg, force = false)
            val state = loadState(cfg, force = args.force)
            runConvert(cfg, analysis, state, only = args.only)
        }
        "report" -> {
            val analysis = loadOrAnalyze(cfg, force = false)
            val state = loadState(cfg, force = false)
            writeReports(cfg, analysis, state, wallMillis = System.currentTimeMillis() - t0)
        }
        "all" -> {
            val initial = runAnalyze(cfg)
            runScaffold(cfg, initial)
            // Scaffold physically moves .java files into KMP layout but
            // doesn't update the in-memory analysis paths. Rebind so
            // runConvert (and any subsequent --phase convert resume)
            // hands J2K paths that actually exist on disk.
            val analysis = jako.phase1.rebindAnalysisToKmpLayout(cfg, initial)
            val state = loadState(cfg, force = args.force)
            runConvert(cfg, analysis, state, only = args.only)
            writeReports(cfg, analysis, state, wallMillis = System.currentTimeMillis() - t0)
        }
        else -> {
            System.err.println("unknown phase: ${args.phase}")
            printHelp()
            exitProcess(2)
        }
    }
}

internal data class Args(
    val config: String = "config.yaml",
    val phase: String = "all",
    val force: Boolean = false,
    val only: List<String> = emptyList(),
    /** Override `project.root` from config.yaml. Both `--project` and
     *  `--module` are optional; if omitted the config file value is used.
     *  Lets the same `gradle :orchestrator:run` invocation point at
     *  different target projects without editing config.yaml. */
    val project: String? = null,
    val module: String? = null,
    /**
     * Run jako against a git worktree of `project.root` instead of the
     * main checkout. The worktree is created on first invocation
     * (idempotent on later runs) and all subsequent phases — scaffold's
     * file moves, convert's commits — happen there. Lets a conversion
     * proceed without ever modifying the source repo's main working
     * tree, while keeping git history shared so commit_per_file still
     * works naturally.
     */
    val worktree: String? = null,
    /** Branch the worktree checks out. Defaults to `jako/<module>`.
     *  If the branch doesn't exist yet, it's created. */
    val worktreeBranch: String? = null,
)

/**
 * Apply CLI overrides on top of the loaded config. Each override is a
 * pure replacement — passing `--project ~/foo` swaps `project.root`
 * outright. Empty string is treated as "not provided" so callers can
 * pass `--project ""` defensively without clobbering the config.
 *
 * **Preserves `Config.base`.** Kotlin's auto-generated `data class
 * copy(...)` only copies primary-constructor properties; the class-body
 * `var base: Path` (where `loadConfig` recorded the config file's
 * directory for relative-path resolution) gets reset to `Path.of(".")`.
 * Without re-applying `base`, every relative path in config.yaml
 * (skills root, scripts/, vendored-skills/, etc.) silently starts
 * resolving against the JVM's CWD the moment a `--project` /
 * `--module` flag is passed.
 */
internal fun Config.withOverrides(args: Args): Config {
    val newRoot = args.project?.takeIf { it.isNotBlank() } ?: this.project.root
    val newModule = args.module?.takeIf { it.isNotBlank() } ?: this.project.module
    if (newRoot == this.project.root && newModule == this.project.module) return this
    return this.copy(project = this.project.copy(root = newRoot, module = newModule))
        .apply { base = this@withOverrides.base }
}

private fun parseArgs(rawArgs: Array<String>): Args {
    var config = "config.yaml"
    var phase = "all"
    var force = false
    val only = mutableListOf<String>()
    var project: String? = null
    var module: String? = null
    var worktree: String? = null
    var worktreeBranch: String? = null

    var i = 0
    while (i < rawArgs.size) {
        when (val arg = rawArgs[i]) {
            "--help", "-h" -> { printHelp(); exitProcess(0) }
            "--config" -> { config = rawArgs.getOrNull(++i) ?: missing(arg) }
            "--phase" -> { phase = rawArgs.getOrNull(++i) ?: missing(arg) }
            "--force" -> force = true
            "--only" -> {
                i++
                while (i < rawArgs.size && !rawArgs[i].startsWith("--")) {
                    only.add(rawArgs[i]); i++
                }
                continue
            }
            "--project" -> { project = rawArgs.getOrNull(++i) ?: missing(arg) }
            "--module" -> { module = rawArgs.getOrNull(++i) ?: missing(arg) }
            "--worktree" -> { worktree = rawArgs.getOrNull(++i) ?: missing(arg) }
            "--worktree-branch" -> { worktreeBranch = rawArgs.getOrNull(++i) ?: missing(arg) }
            else -> {
                System.err.println("unknown argument: $arg")
                printHelp()
                exitProcess(2)
            }
        }
        i++
    }
    return Args(config, phase, force, only, project, module, worktree, worktreeBranch)
}

private fun missing(flag: String): Nothing {
    System.err.println("$flag requires a value")
    exitProcess(2)
}

private fun printHelp() {
    println(
        """
        Thin Java -> Kotlin orchestrator (J2K + JetBrains skill + claude -p).

          --config PATH         config.yaml (default: ./config.yaml)
          --phase PHASE         analyze | scaffold | convert | report | all  (default: all)
          --project PATH        override config's project.root (target Gradle/Maven repo)
          --module NAME         override config's project.module (Gradle subproject)
          --worktree PATH       run in a git worktree of project.root checked out
                                at PATH (created on first invocation, reused after).
                                Source repo's main working tree stays untouched;
                                all per-file commits land on a side branch.
          --worktree-branch B   branch the worktree checks out / creates
                                (default: jako/<module>)
          --force               ignore cached analysis/state, redo work
          --only PATH...        restrict convert to specific source files
          -h, --help            print this help and exit
        """.trimIndent()
    )
}

internal fun loadOrAnalyze(cfg: Config, force: Boolean): AnalysisResult {
    val stateDir = cfg.stateDir()
    val bmFile = stateDir.resolve("build-model.json")
    if (force || !Files.exists(bmFile)) return runAnalyze(cfg)
    // The state JSON shape evolves across versions (e.g. GradleDep replacing
    // a plain List<String>). If decoding fails, re-analyze instead of
    // aborting — the state dir is gitignored and cheap to regenerate.
    return try {
        AnalysisResult.read(stateDir)
    } catch (e: Exception) {
        System.err.println(
            "[analyze] cached state at $stateDir is incompatible (${e.message?.lineSequence()?.firstOrNull()}); re-analyzing.",
        )
        runAnalyze(cfg)
    }
}

internal fun loadState(cfg: Config, force: Boolean): RunState {
    val statePath = cfg.stateDir().resolve("run-state.json")
    val state = if (force) RunState(path = statePath) else RunState.load(statePath)
    state.path = statePath
    return state
}
