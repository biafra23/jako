package jako.runners

import jako.Config
import java.nio.file.Files
import java.nio.file.Path

/**
 * Commit-per-file git wrapper. Silently no-ops if the target project isn't a
 * git repo — the orchestrator stays useful in a sandbox / non-VCS context.
 */
data class GitResult(val ok: Boolean, val message: String)

private fun runGit(cwd: Path, vararg args: String): Pair<Int, String> {
    val cmd = listOf("git", "-C", cwd.toString()) + args
    val pr = runProcess(cmd = cmd, cwd = cwd, timeoutSeconds = 60, mergeStderr = true)
    return pr.exitCode to pr.stdout
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

/**
 * Ensure a git worktree exists at [worktreePath] for the repo rooted at
 * [sourceRepo], checked out on [branch]. Idempotent:
 *
 * - If [worktreePath] is already a worktree of [sourceRepo], return it
 *   (regardless of which branch it currently has — re-checking out is
 *   the user's call).
 * - If [worktreePath] is a path that doesn't exist yet, create it.
 *   Picks `git worktree add <path> <branch>` if [branch] already
 *   exists in the repo, otherwise `git worktree add -b <branch> <path>`.
 * - If [worktreePath] exists but isn't a worktree of [sourceRepo],
 *   fail loudly. Don't try to repurpose someone else's directory.
 *
 * Returns the same [worktreePath] (absolutized) on success.
 */
fun ensureWorktree(sourceRepo: Path, worktreePath: Path, branch: String): Path {
    if (!isRepo(sourceRepo)) {
        error("--worktree requires the project root to be a git repository: $sourceRepo")
    }
    // Canonicalise via `toRealPath()` so symlink-bearing prefixes match.
    // macOS `/tmp` → `/private/tmp` is the common case; without this, the
    // worktree gets created the first run but the second run's path
    // comparison thinks it's a different directory.
    fun canon(p: Path): Path = runCatching { p.toRealPath() }
        .getOrElse { p.toAbsolutePath().normalize() }
    val wt = canon(worktreePath)
    val (listCode, listOut) = runGit(sourceRepo, "worktree", "list", "--porcelain")
    if (listCode != 0) error("git worktree list failed: ${listOut.trim()}")
    val existing = listOut.lineSequence()
        .filter { it.startsWith("worktree ") }
        .map { canon(Path.of(it.removePrefix("worktree ").trim())) }
        .toSet()
    if (wt in existing) return wt
    if (Files.exists(wt)) {
        error(
            "path exists but is not a git worktree of $sourceRepo: $wt. " +
                "Pick a different --worktree path or remove the existing dir."
        )
    }
    val branchExists = runGit(sourceRepo, "show-ref", "--verify", "--quiet", "refs/heads/$branch").first == 0
    val args = if (branchExists) {
        arrayOf("worktree", "add", wt.toString(), branch)
    } else {
        arrayOf("worktree", "add", "-b", branch, wt.toString())
    }
    val (addCode, addOut) = runGit(sourceRepo, *args)
    if (addCode != 0) {
        error("git worktree add failed: ${addOut.trim()}")
    }
    return wt
}
