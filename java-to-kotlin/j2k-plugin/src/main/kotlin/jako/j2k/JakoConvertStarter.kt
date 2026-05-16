package jako.j2k

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ApplicationStarter
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import org.jetbrains.kotlin.j2k.ConverterSettings
import org.jetbrains.kotlin.j2k.J2kConverterExtension
import java.nio.file.Files
import java.nio.file.Path

/**
 * Headless J2K driver — see plan-2-thin-orchestrator.md §2.1.
 *
 * Invoked by jako's orchestrator (when `j2k.strategy: headless_idea`)
 * in one of two shapes:
 *
 *     idea jakoConvert <java_in> <kt_out>
 *     idea jakoConvert --manifest <path>
 *
 * The single-file form is kept for debugging and one-shots. The manifest
 * form is what the orchestrator uses for real runs: a 20s IDE startup is
 * fine to amortize across a batch of files but ruinous per-file. Manifest
 * format is one `<java_in>\t<kt_out>` per line; blank lines and lines
 * starting with `#` are ignored.
 *
 * **Threading rules learned the hard way:**
 * 1. `ApplicationStarter.main()` runs on the IDE's EDT.
 * 2. J2K's internals reject EDT access (`Access from Event Dispatch
 *    Thread is not allowed`) — even wrapped in `WriteIntentReadAction`.
 * 3. So conversion must run on a pooled (background) thread inside a
 *    `ReadAction.compute { ... }`.
 * 4. The pooled thread may need to marshal write actions back to EDT
 *    (PSI mutation, file writes). For that to work, the EDT must keep
 *    pumping events — meaning **`main()` must return immediately**,
 *    not block on `future.get()`. The IDE doesn't terminate when
 *    `main()` returns; it terminates when something calls
 *    `Application.exit()`.
 * 5. We call `app.exit(...)` from the pooled thread once conversion
 *    completes (via `invokeLater` so it lands on the EDT cleanly).
 *
 * Earlier iterations of this file blocked the EDT on `future.get()` and
 * deadlocked after ~25 minutes of nothing happening. Don't reintroduce
 * that pattern.
 */
class JakoConvertStarter : ApplicationStarter {
    private val log get() = thisLogger()

    // `commandName` is flagged deprecated on newer platform builds but
    // is still the documented hook for the `idea <name> <args>` headless
    // invocation we use. Replacement is "ModernApplicationStarter" which
    // is service-loader-based and doesn't fit our process-per-file model.
    @Suppress("OVERRIDE_DEPRECATION")
    override val commandName: String = "jakoConvert"

    override fun main(args: List<String>) {
        // args[0] is the command name itself, args[1..] are positional.
        val tail = args.drop(1)
        val pairs: List<Pair<Path, Path>> = when {
            tail.size == 2 && tail[0] == "--manifest" ->
                parseManifest(Path.of(tail[1]).toAbsolutePath())
            tail.size == 2 && !tail[0].startsWith("--") ->
                listOf(Path.of(tail[0]).toAbsolutePath() to Path.of(tail[1]).toAbsolutePath())
            else -> {
                System.err.println("usage: idea jakoConvert <java_in> <kt_out>")
                System.err.println("   or: idea jakoConvert --manifest <path>")
                haltWith(2)
            }
        }
        if (pairs.isEmpty()) {
            System.err.println("jakoConvert: manifest had no pairs to convert")
            haltWith(2)
        }
        val missing = pairs.filterNot { (javaIn, _) -> Files.isRegularFile(javaIn) }
        if (missing.isNotEmpty()) {
            missing.forEach { (javaIn, _) ->
                System.err.println("jakoConvert: input not a regular file: $javaIn")
            }
            haltWith(1)
        }

        val app = ApplicationManager.getApplication()
        // Hand the work to a pooled thread, then return. The IDE keeps
        // pumping EDT events while conversion runs; the pooled task
        // triggers exit when done.
        app.executeOnPooledThread {
            var anyFailed = false
            for ((javaIn, ktOut) in pairs) {
                runCatching { convert(javaIn) }.fold(
                    onSuccess = { kotlinSource ->
                        runCatching {
                            Files.createDirectories(ktOut.parent)
                            Files.writeString(ktOut, kotlinSource)
                            log.info("jakoConvert: wrote $ktOut (${kotlinSource.length} chars)")
                        }.onFailure { e ->
                            log.warn("jakoConvert: write failed for $ktOut", e)
                            System.err.println("jakoConvert: write $ktOut: ${e.javaClass.simpleName}: ${e.message}")
                            anyFailed = true
                        }
                    },
                    onFailure = { e ->
                        log.warn("jakoConvert: J2K failed for $javaIn", e)
                        System.err.println("jakoConvert: convert $javaIn: ${e.javaClass.simpleName}: ${e.message}")
                        anyFailed = true
                    },
                )
            }
            if (anyFailed) {
                haltWith(1)
            } else {
                app.invokeLater { app.exit(true, true, false) }
            }
        }
    }

    /**
     * Parses a manifest file: one `<java_in>\t<kt_out>` per line. Blank
     * lines and lines beginning with `#` are skipped. Bad lines abort
     * with exit 2 — silently skipping them would mask a corrupted
     * manifest as a much-shorter-than-expected batch.
     */
    private fun parseManifest(path: Path): List<Pair<Path, Path>> {
        if (!Files.isRegularFile(path)) {
            System.err.println("jakoConvert: manifest not found: $path")
            haltWith(1)
        }
        return Files.readAllLines(path)
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { line ->
                val parts = line.split("\t")
                if (parts.size != 2) {
                    System.err.println("jakoConvert: bad manifest line (need <java>\\t<kt>): $line")
                    haltWith(2)
                }
                Path.of(parts[0]).toAbsolutePath() to Path.of(parts[1]).toAbsolutePath()
            }
    }

    /**
     * `defaultProject` is a lightweight singleton project IntelliJ exposes
     * for tools that don't need a real loaded workspace. It has **no SDK,
     * no library dependencies, no project-specific context** — so symbol
     * resolution and type inference are weaker than what the IDE menu
     * gets when converting from a real loaded project. Concrete effects:
     *
     *   - Java types resolving through external libraries (e.g.
     *     `io.vertx.core.buffer.Buffer`) come through as `Any?` because
     *     the classpath doesn't see them.
     *   - Smart casts on cross-file types may not fire.
     *   - Some `@JvmStatic` / interop annotation choices end up overly
     *     conservative.
     *
     * The refine step downstream fixes most of this, which is why we
     * accept the trade-off here — using `defaultProject` keeps the
     * plugin small and the per-file invocation fast (no project import,
     * no indexing wait).
     *
     * A "loaded project" alternative is doable in a follow-up:
     * `ProjectUtil.openOrImport(targetGradleRoot)` plus a "wait for
     * indices ready" gate. Higher fidelity, higher startup cost,
     * many more failure modes (project import can fail for plenty of
     * reasons not under jako's control). Not in this PR.
     */
    private fun convert(javaIn: Path): String {
        val project: Project = ProjectManager.getInstance().defaultProject
        val vfile = LocalFileSystem.getInstance()
            .refreshAndFindFileByNioFile(javaIn)
            ?: error("VFS could not resolve $javaIn")
        VfsUtil.markDirtyAndRefresh(false, false, false, vfile)
        return ReadAction.compute<String, Throwable> {
            val psi = PsiManager.getInstance(project).findFile(vfile) as? PsiJavaFile
                ?: error("not a Java PSI file: $javaIn")
            // The Kind enum is K1_OLD / K1_NEW / K2.
            //
            // - K2: only registers when the IDE runs in K2 mode
            //   (`kotlin.k2.mode=true`). Throws NoSuchElementException
            //   on a stock IDE.
            // - K1_NEW: modern PSI-based converter, the IDE menu default.
            //   But its `NewJ2kPostProcessor.doAdditionalProcessing`
            //   throws "Invalid converter context for new J2K" when invoked
            //   outside the IDE's normal pipeline (the context is set up
            //   by `NewJavaToKotlinConverter` internals that the menu
            //   action goes through but we don't).
            // - K1_OLD: legacy converter. Less polished output (the new
            //   converter does more idiom cleanup) but its post-processor
            //   has no hidden context dependency, so it works headlessly.
            //
            // Refine downstream cleans up the idioms anyway, so the
            // tradeoff is fine: trade "fancier raw output" for "actually
            // works in a headless ApplicationStarter."
            val ext = J2kConverterExtension.extension(J2kConverterExtension.Kind.K1_OLD)
            val converter = ext.createJavaToKotlinConverter(
                project,
                /* targetModule = */ null,
                ConverterSettings.defaultSettings,
                /* targetFile = */ null,
            )
            val res = converter.filesToKotlin(
                listOf(psi),
                ext.createPostProcessor(/* formatCode = */ true),
                EmptyProgressIndicator(),
            )
            res.results.firstOrNull()
                ?: error("J2K produced no result for $javaIn")
        }
    }

    /**
     * `Application.exit` always returns exit code 0, which doesn't let
     * us signal "fix something and try again" to the orchestrator.
     * `System.exit(code)` is the standard way to terminate with a
     * specific code — it runs shutdown hooks (Disposer cleanup, VFS
     * flush, etc.) on its way out, then calls `Runtime.halt(code)`
     * internally.
     *
     * Earlier this used `Runtime.halt(code)` directly to skip the
     * hook overhead, but the hooks aren't actually slow enough to
     * matter on a process-per-file tool, and skipping them risks
     * dropping log entries / leaving sandbox state inconsistent on
     * the rare failure path. Prefer the conventional choice.
     */
    private fun haltWith(code: Int): Nothing {
        System.exit(code)
        throw IllegalStateException("System.exit didn't terminate the JVM")
    }
}
