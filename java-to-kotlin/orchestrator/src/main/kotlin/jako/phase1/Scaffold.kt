package jako.phase1

import jako.AnalysisResult
import jako.Config
import jako.GradleDep
import jako.runners.invokeSkillOneShot
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.exists

/**
 * Phase 1 — KMP scaffolding.
 *
 * Generates `build.gradle.kts` in KMP shape, moves the original `.java`
 * tree into `src/jvmMain/java` (and `src/jvmTest/java` for tests). Nothing
 * here is LLM-driven except an optional one-shot pass over the generated
 * build files with the JetBrains `kotlin-tooling-agp9-migration` skill
 * (only if the project applies an Android plugin).
 *
 * Idempotent: re-running on an already-scaffolded module is a no-op.
 */

data class ScaffoldResult(
    val movedMain: Int,
    val movedTest: Int,
    val buildFile: Path,
    val agp9Invoked: Boolean,
    /** Number of `.java` files set aside (as `<java>.scaffold-skip.bak`)
     *  because a converted `.kt` already exists at the destination.
     *  Counted across main + test. Defaulted to 0 so existing callers
     *  that construct `ScaffoldResult(...)` positionally keep compiling. */
    val skippedAlreadyConverted: Int = 0,
)

private data class MoveResult(val moved: Int, val skipped: Int)

private fun moduleRoot(cfg: Config): Path {
    val root = cfg.projectRoot()
    return if (cfg.project.module.isNotBlank() && cfg.project.module != ".")
        root.resolve(cfg.project.module) else root
}

/**
 * Move every `.java` under [src] into the parallel position under [dst].
 *
 * If a sibling `.kt` already exists at the post-conversion location
 * (computed via [convertedKtFor]), the `.java` is **moved aside** to
 * `<dst-path>.scaffold-skip.bak` instead of being deleted — that file
 * was already converted by a previous Phase 2 run, but we keep the
 * Java sibling as a recoverable artifact in case the `.kt` turns out
 * to be stale or wrong. The orchestrator won't compile a `.bak` file,
 * and Phase 2's J2K wrapper produces its own `<kt>.java.bak` for live
 * conversions, so the two backup conventions don't clash.
 *
 * `convertedKtFor` may be `null` when the caller doesn't track converted
 * files (e.g. moving tests in a phase where no `.kt` would exist yet).
 */
private fun moveTree(
    src: Path,
    dst: Path,
    convertedKtFor: ((Path) -> Path)? = null,
): MoveResult {
    if (!src.exists()) return MoveResult(0, 0)
    if (src.toRealPath() == dst.toAbsolutePath().normalize()) return MoveResult(0, 0)
    Files.createDirectories(dst)
    var moved = 0
    var skipped = 0
    Files.walk(src).use { stream ->
        val files = stream.filter { Files.isRegularFile(it) && it.toString().endsWith(".java") }.toList()
        for (path in files) {
            val rel = src.relativize(path)
            val target = dst.resolve(rel)
            val alreadyConverted = convertedKtFor?.invoke(target)?.let {
                Files.exists(it) && Files.size(it) > 0
            } ?: false
            if (alreadyConverted) {
                // Move-aside rather than delete so the .java is recoverable
                // if the existing .kt turns out to be stale or wrong.
                val sideline = target.resolveSibling(target.fileName.toString() + ".scaffold-skip.bak")
                Files.createDirectories(sideline.parent)
                Files.move(path, sideline, StandardCopyOption.REPLACE_EXISTING)
                skipped++
                continue
            }
            Files.createDirectories(target.parent)
            Files.move(path, target, StandardCopyOption.REPLACE_EXISTING)
            moved++
        }
    }
    // Sweep empty dirs.
    Files.walk(src).use { stream ->
        stream.sorted(Comparator.reverseOrder())
            .filter { Files.isDirectory(it) }
            .forEach { p -> if (Files.list(p).use { l -> !l.findFirst().isPresent }) Files.deleteIfExists(p) }
    }
    return MoveResult(moved, skipped)
}

/**
 * Generate a Kotlin-DSL build file that uses the JVM Kotlin plugin already
 * supplied by the parent project's `allprojects { apply plugin: 'kotlin' }`
 * (the common case for multi-project Gradle repos). We override `main` and
 * `test` source sets to point at `src/jvmMain/{java,kotlin}` and
 * `src/jvmTest/{java,kotlin}` — KMP-style layout, JVM-only plugin.
 *
 * The real `kotlin("multiplatform")` plugin is deferred to phase 3 (M6),
 * when `commonMain` becomes relevant. Re-applying it here would collide
 * with the parent's JVM plugin on most real-world multi-project builds
 * (parent applies `apply plugin: 'kotlin'` + `apply plugin: 'java-library'`,
 * both of which clash with KMP's `withJava()` integration).
 *
 * Each dep is emitted in the Gradle configuration it was originally
 * declared in (api / implementation / compileOnly / runtimeOnly /
 * testImplementation / testCompileOnly / testRuntimeOnly / annotationProcessor).
 */
/**
 * Render a dependency value: Maven coords as a quoted string, project
 * deps (`coordinate` starts with `project:`) as a `project("…")` call.
 */
private fun renderDepValue(coord: String): String =
    if (coord.startsWith("project:")) "project(\"${coord.removePrefix("project:")}\")"
    else "\"$coord\""

private fun renderBuildKts(deps: List<GradleDep>, usesAgp: Boolean): String {
    // AGP modules need an `android { sourceSets["main"].java.setSrcDirs(...) }`
    // block instead of the top-level `sourceSets {}` extension, and the Kotlin
    // extension is `KotlinAndroidProjectExtension`, not the JVM one. We don't
    // have a tested Android target yet, so emitting unverified Android
    // scaffolding would be guessing. Fail loudly until someone runs the
    // orchestrator against a real AGP target and we can verify.
    if (usesAgp) error(
        "Phase 1 scaffold doesn't yet support Android modules (AGP plugin detected). " +
        "Run the JetBrains kotlin-tooling-agp9-migration skill manually for now, or " +
        "extend renderBuildKts() to emit an android { sourceSets[…] } block."
    )
    return buildString {
        appendLine("// Generated by jako.phase1.Scaffold.")
        appendLine("//")
        appendLine("// Uses the JVM Kotlin plugin applied by the parent project's allprojects")
        appendLine("// block. Source dirs are overridden to the KMP layout (jvmMain/jvmTest).")
        appendLine("// The real kotlin(\"multiplatform\") plugin is deferred to phase 3.")
        appendLine()
        appendLine("sourceSets {")
        appendLine("    named(\"main\") {")
        appendLine("        java.setSrcDirs(listOf(\"src/jvmMain/java\"))")
        appendLine("        resources.setSrcDirs(listOf(\"src/jvmMain/resources\"))")
        appendLine("    }")
        appendLine("    named(\"test\") {")
        appendLine("        java.setSrcDirs(listOf(\"src/jvmTest/java\"))")
        appendLine("        resources.setSrcDirs(listOf(\"src/jvmTest/resources\"))")
        appendLine("    }")
        appendLine("}")
        appendLine()
        // Use the base KotlinProjectExtension so the same scaffold compiles
        // whether the parent applies kotlin("jvm") or another JVM-flavored
        // Kotlin plugin; the JVM-specific sub-extension would force a
        // narrower contract than we need just to set source dirs.
        appendLine("extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinProjectExtension>(\"kotlin\") {")
        appendLine("    // Include the java dirs in Kotlin's source set so the Kotlin")
        appendLine("    // compiler can see Java types from the same module — it parses")
        appendLine("    // .java sources directly for cross-language resolution, and")
        appendLine("    // `setSrcDirs` *replaces* the default (which already implicitly")
        appendLine("    // includes java/). Without this, every Kotlin reference to a")
        appendLine("    // Java type in the same module fails `Unresolved reference`.")
        appendLine("    sourceSets.named(\"main\") {")
        appendLine("        kotlin.setSrcDirs(listOf(\"src/jvmMain/kotlin\", \"src/jvmMain/java\"))")
        appendLine("    }")
        appendLine("    sourceSets.named(\"test\") {")
        appendLine("        kotlin.setSrcDirs(listOf(\"src/jvmTest/kotlin\", \"src/jvmTest/java\"))")
        appendLine("    }")
        appendLine("}")
        if (deps.isNotEmpty()) {
            appendLine()
            appendLine("dependencies {")
            // The string-config form `"configuration"(coord)` works uniformly
            // for any configuration name, including ones the Kotlin DSL doesn't
            // generate static accessors for (e.g. `annotationProcessor` on
            // modules without the `java` plugin applied at the subproject level).
            for (d in deps) appendLine("    \"${d.configuration}\"(${renderDepValue(d.coordinate)})")
            appendLine("}")
        }
    }
}

fun scaffold(cfg: Config, analysis: AnalysisResult): ScaffoldResult {
    val modRoot = moduleRoot(cfg)

    // 1. Move source trees into KMP layout.
    // After a partial Phase 2 run, some `.java` files may already have a
    // converted `.kt` sibling. Re-introducing them would either duplicate
    // the class at jar-time or leave a stale `.java` in the source tree.
    // Map each `.java` destination to its expected `.kt` and let moveTree
    // delete-instead-of-move when that .kt already exists with content.
    val mainKtRoot = modRoot.resolve("src/jvmMain/kotlin")
    val mainJavaRoot = modRoot.resolve("src/jvmMain/java")
    val testKtRoot = modRoot.resolve("src/jvmTest/kotlin")
    val testJavaRoot = modRoot.resolve("src/jvmTest/java")
    val mainKtFor = { javaTarget: Path ->
        mainKtRoot.resolve(mainJavaRoot.relativize(javaTarget).toString().removeSuffix(".java") + ".kt")
    }
    val testKtFor = { javaTarget: Path ->
        testKtRoot.resolve(testJavaRoot.relativize(javaTarget).toString().removeSuffix(".java") + ".kt")
    }
    val mainRes = moveTree(modRoot.resolve("src/main/java"), mainJavaRoot, mainKtFor)
    val testRes = moveTree(modRoot.resolve("src/test/java"), testJavaRoot, testKtFor)
    Files.createDirectories(modRoot.resolve("src/jvmMain/kotlin"))

    // 2. Render KMP build file, backing up any existing one.
    val buildFile = modRoot.resolve("build.gradle.kts")
    val legacy = modRoot.resolve("build.gradle")
    if (Files.exists(buildFile)) {
        val backup = modRoot.resolve("build.gradle.kts.pre-kmp.bak")
        if (!Files.exists(backup)) Files.copy(buildFile, backup)
    }
    if (Files.exists(legacy)) {
        val backup = modRoot.resolve("build.gradle.pre-kmp.bak")
        if (!Files.exists(backup)) Files.copy(legacy, backup)
        // Gradle prefers `build.gradle` over `build.gradle.kts` when both
        // exist; leaving the legacy file in place would silently keep using
        // the pre-KMP Groovy config. Remove it after backup.
        Files.delete(legacy)
    }
    Files.writeString(
        buildFile,
        renderBuildKts(analysis.buildModel.gradleDependencies, analysis.buildModel.pluginUsesAgp),
    )

    // 3. Optional AGP9 sanity pass.
    var agp9Invoked = false
    if (analysis.buildModel.pluginUsesAgp) {
        val skill = cfg.skillPath(cfg.skills.agp9Migration)
        if (Files.exists(skill)) {
            invokeSkillOneShot(
                cfg,
                skillPath = skill,
                userPrompt = "Apply the AGP 9 migration to this Gradle module. " +
                    "Working directory: $modRoot. Only edit build files. Do not deliberate.",
                cwd = modRoot,
            )
            agp9Invoked = true
        }
    }

    return ScaffoldResult(
        movedMain = mainRes.moved,
        movedTest = testRes.moved,
        skippedAlreadyConverted = mainRes.skipped + testRes.skipped,
        buildFile = buildFile,
        agp9Invoked = agp9Invoked,
    )
}

fun runScaffold(cfg: Config, analysis: AnalysisResult): ScaffoldResult {
    val res = scaffold(cfg, analysis)
    println("[scaffold] moved ${res.movedMain} main + ${res.movedTest} test files into KMP layout")
    if (res.skippedAlreadyConverted > 0) {
        println("[scaffold] set aside ${res.skippedAlreadyConverted} .java already converted to .kt (.scaffold-skip.bak)")
    }
    println("[scaffold] wrote ${res.buildFile}")
    if (res.agp9Invoked) println("[scaffold] AGP9 migration skill applied")
    return res
}

/**
 * Returns a copy of [analysis] with every `unit.sourcePath` and every
 * `order[*]` entry rewritten from the Maven layout (`src/main/java/...`
 * / `src/test/java/...`) to the KMP layout the scaffold phase just laid
 * down on disk (`src/jvmMain/java/...` / `src/jvmTest/java/...`).
 *
 * Why this matters: `runAnalyze` records each unit's `sourcePath` against
 * the *pre*-scaffold filesystem. `runScaffold` then physically moves the
 * .java files but doesn't rewrite the in-memory analysis. Without this
 * rebinding, `runConvert` hands J2K paths that no longer exist on disk
 * (every "missing input" failure in M3-style first runs traced back to
 * this). `relativePath` is package-relative (e.g.
 * `org/apache/tuweni/bytes/Foo.java`) and therefore layout-independent —
 * left alone.
 *
 * Also re-persists `source-inventory.json` / `convert-order.json` /
 * `risk-classification.json` so a subsequent `--phase convert` run loads
 * the post-scaffold paths.
 */
fun rebindAnalysisToKmpLayout(cfg: Config, analysis: AnalysisResult): AnalysisResult {
    val modRoot = moduleRoot(cfg)
    val mainOld = modRoot.resolve("src/main/java").toString()
    val mainNew = modRoot.resolve("src/jvmMain/java").toString()
    val testOld = modRoot.resolve("src/test/java").toString()
    val testNew = modRoot.resolve("src/jvmTest/java").toString()

    fun rebind(path: String): String = when {
        path.startsWith("$mainOld/") -> mainNew + path.substring(mainOld.length)
        path.startsWith("$testOld/") -> testNew + path.substring(testOld.length)
        else -> path
    }

    val newUnits = analysis.units.map { u ->
        val rebound = rebind(u.sourcePath)
        if (rebound == u.sourcePath) u else u.copy(sourcePath = rebound)
    }
    val newOrder = analysis.order.map { batch -> batch.map(::rebind) }
    val newCycles = newOrder.filter { it.size > 1 }
    val updated = analysis.copy(units = newUnits, order = newOrder, cycles = newCycles)
    updated.write(cfg.stateDir())
    return updated
}
