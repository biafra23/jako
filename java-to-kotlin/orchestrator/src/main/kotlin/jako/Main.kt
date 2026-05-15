package jako

import jako.phase0.runAnalyze
import jako.phase1.runScaffold
import jako.phase2.runConvert
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
    val cfg = loadConfig(cfgPath)

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
            val analysis = runAnalyze(cfg)
            runScaffold(cfg, analysis)
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
)

private fun parseArgs(rawArgs: Array<String>): Args {
    var config = "config.yaml"
    var phase = "all"
    var force = false
    val only = mutableListOf<String>()

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
            else -> {
                System.err.println("unknown argument: $arg")
                printHelp()
                exitProcess(2)
            }
        }
        i++
    }
    return Args(config, phase, force, only)
}

private fun missing(flag: String): Nothing {
    System.err.println("$flag requires a value")
    exitProcess(2)
}

private fun printHelp() {
    println(
        """
        Thin Java -> Kotlin orchestrator (J2K + JetBrains skill + claude -p).

          --config PATH        config.yaml (default: ./config.yaml)
          --phase PHASE        analyze | scaffold | convert | report | all  (default: all)
          --force              ignore cached analysis/state, redo work
          --only PATH...       restrict convert to specific source files
          -h, --help           print this help and exit
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
