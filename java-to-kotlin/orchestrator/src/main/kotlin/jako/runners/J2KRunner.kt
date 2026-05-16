package jako.runners

import jako.Config
import java.nio.file.Files
import java.nio.file.Path

/**
 * Per-file backend chain step 1 — mechanical Java→Kotlin via JetBrains J2K.
 *
 * Three strategies, controlled by config.j2k.strategy:
 *   passthrough   - no J2K available; we just `cp` the .java into the .kt
 *                   slot and let the LLM step do the conversion. Default.
 *   external      - `command` is a script that takes <java_in> <kt_out>.
 *                   Use this with scripts/passthrough-j2k.sh or your own
 *                   wrapper around any external converter.
 *   headless_idea - drive IntelliJ's idea.sh with our ApplicationStarter
 *                   plugin. Requires the plugin to be built and installed
 *                   into IDEA (separate Gradle subproject — not in this
 *                   repo yet).
 */
data class J2KResult(
    val ok: Boolean,
    val ktPath: Path,
    val stderrTail: String = "",
    val elapsedSeconds: Double = 0.0,
)

/**
 * Per-file outcomes for a batch invocation, keyed by **java input path**
 * (the .java file the orchestrator wanted converted). Callers map back to
 * their unit via the same key.
 */
data class J2KBatchResult(
    val results: Map<Path, J2KResult>,
    val elapsedSeconds: Double,
)

fun describeJ2K(cfg: Config): String {
    val cmd = if (cfg.j2k.command.isBlank()) "<unset>" else cfg.j2k.command
    return "j2k: strategy=${cfg.j2k.strategy} command=$cmd"
}

private fun buildCommand(cfg: Config, javaIn: Path, ktOut: Path): List<String> {
    if (cfg.j2k.command.isBlank()) {
        error("j2k.command is empty in config.yaml — set it (e.g. to scripts/passthrough-j2k.sh).")
    }
    val resolved = cfg.resolve(cfg.j2k.command).toString()
    return when (cfg.j2k.strategy) {
        "external", "passthrough" -> buildList {
            add(resolved); addAll(cfg.j2k.args)
            add(javaIn.toString()); add(ktOut.toString())
        }
        "headless_idea" -> buildList {
            add(resolved); addAll(cfg.j2k.args)
            // The exact args depend on the ApplicationStarter the plugin registers.
            // Convention: starter name is passed in cfg.j2k.args; we append the
            // two file paths.
            add(javaIn.toString()); add(ktOut.toString())
        }
        else -> error("unknown j2k.strategy: ${cfg.j2k.strategy}")
    }
}

private fun buildBatchCommand(cfg: Config, manifest: Path): List<String> {
    if (cfg.j2k.command.isBlank()) {
        error("j2k.command is empty in config.yaml — set it (e.g. to scripts/run-j2k-headless.sh).")
    }
    val resolved = cfg.resolve(cfg.j2k.command).toString()
    return buildList {
        add(resolved); addAll(cfg.j2k.args)
        add("--manifest"); add(manifest.toString())
    }
}

fun convertJ2K(cfg: Config, javaIn: Path, ktOut: Path): J2KResult {
    Files.createDirectories(ktOut.parent)
    val cmd = buildCommand(cfg, javaIn, ktOut)
    val pr = runProcess(cmd = cmd, cwd = ktOut.parent, timeoutSeconds = cfg.j2k.timeoutSeconds)
    if (pr.exitCode == -1) {
        return J2KResult(false, ktOut, "J2K timed out\n${pr.stderr}", pr.elapsedSeconds)
    }
    if (pr.exitCode != 0) {
        val tail = (pr.stderr.ifBlank { pr.stdout }).takeLast(2000)
        return J2KResult(false, ktOut, tail, pr.elapsedSeconds)
    }
    if (!Files.exists(ktOut) || Files.size(ktOut) == 0L) {
        return J2KResult(false, ktOut, "J2K returned 0 but no .kt file was produced", pr.elapsedSeconds)
    }
    return J2KResult(true, ktOut, elapsedSeconds = pr.elapsedSeconds)
}

/**
 * Convert a batch of files in a single invocation. For `headless_idea`
 * (and `external`, which can opt in by pointing `j2k.command` at a script
 * that handles `--manifest`), this writes a temp manifest and runs the
 * script once — amortizing the ~20s IDE startup across the whole batch.
 *
 * For `passthrough`, batching gives nothing (a `cp` is already cheap), so
 * we just iterate `convertJ2K` per pair. Same result type either way.
 *
 * Per-file success is decided by inspecting the .kt file on disk after the
 * batch returns: present + non-empty = ok. The batch process's stderr/stdout
 * tail attaches to every failing entry so the orchestrator can surface it.
 * Granular per-file diagnostics would need a richer protocol with the
 * script; not worth it until we hit a case where the shared tail isn't
 * enough to debug.
 */
fun convertJ2KBatch(cfg: Config, pairs: List<Pair<Path, Path>>): J2KBatchResult {
    if (pairs.isEmpty()) return J2KBatchResult(emptyMap(), 0.0)

    val t0 = System.currentTimeMillis()
    if (cfg.j2k.strategy == "passthrough") {
        val results = pairs.associate { (javaIn, ktOut) ->
            javaIn to convertJ2K(cfg, javaIn, ktOut)
        }
        return J2KBatchResult(results, (System.currentTimeMillis() - t0) / 1000.0)
    }

    pairs.forEach { (_, ktOut) -> Files.createDirectories(ktOut.parent) }
    val manifest = Files.createTempFile("jako-j2k-manifest-", ".tsv")
    try {
        val text = pairs.joinToString("\n") { (javaIn, ktOut) -> "$javaIn\t$ktOut" } + "\n"
        Files.writeString(manifest, text)

        val cmd = buildBatchCommand(cfg, manifest)
        val cwd = pairs.first().second.parent
        val pr = runProcess(cmd = cmd, cwd = cwd, timeoutSeconds = cfg.j2k.timeoutSeconds)
        val elapsed = pr.elapsedSeconds

        val sharedTail = when {
            pr.exitCode == -1 -> "J2K batch timed out after ${cfg.j2k.timeoutSeconds}s\n${pr.stderr.takeLast(2000)}"
            pr.exitCode != 0 -> "J2K batch exited ${pr.exitCode}\n${(pr.stderr.ifBlank { pr.stdout }).takeLast(2000)}"
            else -> ""
        }

        val results = pairs.associate { (javaIn, ktOut) ->
            val ok = Files.exists(ktOut) && Files.size(ktOut) > 0
            val tail = when {
                ok -> ""
                sharedTail.isNotBlank() -> sharedTail
                else -> "J2K batch returned 0 but no .kt produced for $javaIn"
            }
            javaIn to J2KResult(
                ok = ok,
                ktPath = ktOut,
                stderrTail = tail,
                elapsedSeconds = elapsed / pairs.size,
            )
        }
        return J2KBatchResult(results, elapsed)
    } finally {
        runCatching { Files.deleteIfExists(manifest) }
    }
}
