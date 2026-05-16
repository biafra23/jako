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
 * as:
 *
 *     idea jakoConvert <java_in> <kt_out>
 *
 * Drives the bundled JetBrains Java→Kotlin converter (the same one the
 * IDE menu uses) on a single Java file, writes the resulting Kotlin to
 * `<kt_out>`, exits the application.
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
        if (args.size != 3) {
            System.err.println("usage: idea jakoConvert <java_in> <kt_out>")
            haltWith(2)
            return
        }
        val javaIn = Path.of(args[1]).toAbsolutePath()
        val ktOut = Path.of(args[2]).toAbsolutePath()

        if (!Files.isRegularFile(javaIn)) {
            System.err.println("jakoConvert: input not a regular file: $javaIn")
            haltWith(1)
            return
        }

        val app = ApplicationManager.getApplication()
        // Hand the work to a pooled thread, then return. The IDE keeps
        // pumping EDT events while conversion runs; the pooled task
        // triggers exit when done.
        app.executeOnPooledThread {
            val result = runCatching { convert(javaIn) }
            result.fold(
                onSuccess = { kotlinSource ->
                    runCatching {
                        Files.createDirectories(ktOut.parent)
                        Files.writeString(ktOut, kotlinSource)
                    }.onFailure { e ->
                        log.warn("jakoConvert: write failed for $ktOut", e)
                        System.err.println("jakoConvert: ${e.javaClass.simpleName}: ${e.message}")
                        haltWith(1)
                        return@executeOnPooledThread
                    }
                    log.info("jakoConvert: wrote $ktOut (${kotlinSource.length} chars)")
                    app.invokeLater { app.exit(true, true, false) }
                },
                onFailure = { e ->
                    log.warn("jakoConvert: J2K failed for $javaIn", e)
                    System.err.println("jakoConvert: ${e.javaClass.simpleName}: ${e.message}")
                    haltWith(1)
                },
            )
        }
    }

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
     * `Runtime.halt(code)` bypasses shutdown hooks and exits with the
     * requested code. The shutdown hooks we'd skip (Disposer cleanup,
     * VFS flush) don't matter for a process-per-file headless tool.
     */
    private fun haltWith(code: Int): Nothing = Runtime.getRuntime().halt(code) as Nothing
}
