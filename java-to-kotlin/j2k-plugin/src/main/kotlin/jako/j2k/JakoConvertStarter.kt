package jako.j2k

import com.intellij.ide.impl.OpenProjectTask
import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ApplicationStarter
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.project.DumbService
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
 * in one of these shapes:
 *
 *     idea jakoConvert <java_in> <kt_out>
 *     idea jakoConvert --manifest <path>
 *     idea jakoConvert --project <root> <java_in> <kt_out>
 *     idea jakoConvert --project <root> --manifest <path>
 *
 * The single-file form is kept for debugging and one-shots. The manifest
 * form is what the orchestrator uses for real runs: a 20s IDE startup is
 * fine to amortize across a batch of files but ruinous per-file. Manifest
 * format is one `<java_in>\t<kt_out>` per line; blank lines and lines
 * starting with `#` are ignored.
 *
 * `--project <root>` opens the target project (Gradle / Maven / generic),
 * waits for indexing to settle, and uses *that* project's PSI for the
 * conversion. Cross-file types resolve through the project's classpath
 * instead of through `defaultProject`'s nothing — J2K produces noticeably
 * better Kotlin (proper types, smart casts, idiomatic interop) when it
 * can see the classpath. Without `--project` the converter falls back to
 * `defaultProject` (fast startup, weaker output — see `convert` KDoc).
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

    private data class Parsed(
        val projectRoot: Path?,
        val pairs: List<Pair<Path, Path>>,
    )

    override fun main(args: List<String>) {
        // args[0] is the command name itself, args[1..] are positional.
        val parsed = parseArgs(args.drop(1))
        if (parsed.pairs.isEmpty()) {
            System.err.println("jakoConvert: nothing to convert")
            haltWith(2)
        }
        val missing = parsed.pairs.filterNot { (javaIn, _) -> Files.isRegularFile(javaIn) }
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
            // Project load is slow (Gradle sync + indexing on first run);
            // do it on the pooled thread, not the EDT. openProject bounces
            // the actual `openOrImport` call back to EDT via invokeAndWait.
            val project: Project = if (parsed.projectRoot != null) {
                runCatching { openProject(parsed.projectRoot) }.getOrElse { e ->
                    log.warn("jakoConvert: failed to open project ${parsed.projectRoot}", e)
                    System.err.println(
                        "jakoConvert: open project ${parsed.projectRoot}: " +
                            "${e.javaClass.simpleName}: ${e.message}",
                    )
                    haltWith(1)
                }
            } else {
                ProjectManager.getInstance().defaultProject
            }

            var anyFailed = false
            for ((javaIn, ktOut) in parsed.pairs) {
                runCatching { convert(project, javaIn) }.fold(
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
     * Linear arg parser. Recognises an optional leading `--project <root>`
     * followed by either a `--manifest <path>` flag or a `<java_in> <kt_out>`
     * pair. Anything else aborts with exit 2 — silently doing the wrong
     * thing on a malformed call is worse than a hard fail.
     */
    private fun parseArgs(rawArgs: List<String>): Parsed {
        val rest = rawArgs.toMutableList()
        var projectRoot: Path? = null
        if (rest.size >= 2 && rest[0] == "--project") {
            projectRoot = Path.of(rest[1]).toAbsolutePath()
            rest.subList(0, 2).clear()
        }
        val pairs: List<Pair<Path, Path>> = when {
            rest.size == 2 && rest[0] == "--manifest" ->
                parseManifest(Path.of(rest[1]).toAbsolutePath())
            rest.size == 2 && !rest[0].startsWith("--") ->
                listOf(Path.of(rest[0]).toAbsolutePath() to Path.of(rest[1]).toAbsolutePath())
            else -> {
                System.err.println("usage: idea jakoConvert [--project <root>] <java_in> <kt_out>")
                System.err.println("   or: idea jakoConvert [--project <root>] --manifest <path>")
                haltWith(2)
            }
        }
        return Parsed(projectRoot, pairs)
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
     * Opens `root` as an IntelliJ project on the EDT (where project open
     * lives) and blocks until it's smart, i.e. indexing has settled.
     *
     * For a Gradle project this triggers the Gradle ProjectOpenProcessor,
     * which kicks off `Sync Project With Gradle Files` — the dependency
     * resolution + indexing pass. First run on a fresh `~/.gradle/caches`
     * downloads everything and can take minutes; subsequent runs reuse
     * `.idea/` + the gradle cache and complete in tens of seconds.
     *
     * Once `waitForSmartMode()` returns the loaded project has a proper
     * module graph + classpath, which is what J2K's type inference needs
     * to produce idiomatic Kotlin.
     *
     * On a non-Gradle / non-Maven directory the open still succeeds but
     * the result is closer to `defaultProject` — no module-level classpath,
     * just source roots. That's a reasonable degraded mode, not an error.
     */
    private fun openProject(root: Path): Project {
        require(Files.isDirectory(root)) { "project root must be a directory: $root" }
        log.info("jakoConvert: opening project at $root")
        val app = ApplicationManager.getApplication()
        var project: Project? = null
        app.invokeAndWait {
            project = ProjectUtil.openOrImport(root, OpenProjectTask())
        }
        val p = project ?: error("ProjectUtil.openOrImport returned null for $root")
        log.info("jakoConvert: project opened, waiting for indexing to settle")
        DumbService.getInstance(p).waitForSmartMode()
        log.info("jakoConvert: project ready: ${p.name}")
        return p
    }

    /**
     * Conversion in one of two contexts:
     *
     * - **Loaded project** (`--project` passed) — `findModuleForFile` finds
     *   the module containing this `.java`, so J2K's converter is created
     *   with `targetModule = <that module>`. Type resolution goes through
     *   the module's full classpath: cross-file types resolve, library
     *   types come through with the right names, smart casts fire.
     *
     * - **`defaultProject` fallback** (no `--project`) — `defaultProject`
     *   is a lightweight singleton IntelliJ exposes for tools that don't
     *   need a real workspace. No SDK, no library dependencies, no
     *   project-specific context. Java types resolving through external
     *   libraries (e.g. `io.vertx.core.buffer.Buffer`) come through as
     *   `Any?`; smart casts on cross-file types may not fire; some
     *   `@JvmStatic` / interop annotation choices end up overly
     *   conservative. The refine step downstream fixes most of this.
     *
     * Both modes go through the same J2K extension and the same K1_OLD
     * post-processor — only the `Project`/`Module` context differs.
     */
    private fun convert(project: Project, javaIn: Path): String {
        val vfile = LocalFileSystem.getInstance()
            .refreshAndFindFileByNioFile(javaIn)
            ?: error("VFS could not resolve $javaIn")
        VfsUtil.markDirtyAndRefresh(false, false, false, vfile)
        return ReadAction.compute<String, Throwable> {
            val psi = PsiManager.getInstance(project).findFile(vfile) as? PsiJavaFile
                ?: error("not a Java PSI file: $javaIn")
            // `defaultProject` has no modules, so `findModuleForFile`
            // returns null and we pass null to the converter — same as
            // before. A loaded Gradle project will have modules and
            // J2K picks up the classpath through it.
            val module = ModuleUtilCore.findModuleForFile(psi)
            log.info(
                "jakoConvert: converting $javaIn " +
                    "(project=${project.name}, module=${module?.name ?: "<none>"})",
            )
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
                module,
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
