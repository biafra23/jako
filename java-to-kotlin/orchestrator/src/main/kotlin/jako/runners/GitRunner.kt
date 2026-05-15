package jako.runners

import jako.Config
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Commit-per-file git wrapper. Silently no-ops if the target project isn't a
 * git repo — the orchestrator stays useful in a sandbox / non-VCS context.
 */
data class GitResult(val ok: Boolean, val message: String)

private fun runGit(cwd: Path, vararg args: String): Pair<Int, String> {
    val cmd = listOf("git", "-C", cwd.toString()) + args
    val pb = ProcessBuilder(cmd).redirectErrorStream(true)
    val proc = pb.start()
    val out = proc.inputStream.bufferedReader().readText()
    proc.waitFor(60, TimeUnit.SECONDS)
    return proc.exitValue() to out
}

private fun isRepo(root: Path): Boolean {
    val (code, out) = runGit(root, "rev-parse", "--is-inside-work-tree")
    return code == 0 && out.trim() == "true"
}

fun commitFiles(cfg: Config, pathsToAdd: List<Path>, message: String): GitResult {
    if (!cfg.git.commitPerFile) return GitResult(true, "commit_per_file=false; skipped")
    val root = cfg.projectRoot()
    if (!isRepo(root)) return GitResult(true, "not a git repo; skipped")

    val (addCode, addOut) = runGit(root, "add", "--", *pathsToAdd.map { it.toString() }.toTypedArray())
    if (addCode != 0) return GitResult(false, "git add failed: ${addOut.trim()}")

    val commitArgs = buildList {
        add("commit"); add("-m"); add(message)
        if (cfg.git.author.isNotBlank()) { add("--author"); add(cfg.git.author) }
    }
    val (commitCode, commitOut) = runGit(root, *commitArgs.toTypedArray())
    if (commitCode != 0) {
        if ("nothing to commit" in commitOut.lowercase()) return GitResult(true, "nothing to commit")
        return GitResult(false, "git commit failed: ${commitOut.trim()}")
    }
    return GitResult(true, "committed")
}

fun revertLast(cfg: Config): GitResult {
    val root = cfg.projectRoot()
    if (!isRepo(root) || !cfg.git.commitPerFile) return GitResult(true, "revert: nothing to do")
    val (code, out) = runGit(root, "reset", "--hard", "HEAD~1")
    return if (code == 0) GitResult(true, "reverted last commit")
    else GitResult(false, "git reset failed: ${out.trim()}")
}

fun discardUnstaged(cfg: Config, paths: List<Path>) {
    val root = cfg.projectRoot()
    if (!isRepo(root) || paths.isEmpty()) return
    runGit(root, "checkout", "--", *paths.map { it.toString() }.toTypedArray())
}
