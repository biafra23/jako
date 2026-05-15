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
