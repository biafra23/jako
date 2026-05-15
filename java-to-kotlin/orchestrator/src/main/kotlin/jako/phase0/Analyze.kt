package jako.phase0

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.body.TypeDeclaration
import jako.AnalysisResult
import jako.BuildModel
import jako.Config
import jako.GradleDep
import jako.JavaSourceUnit
import jako.fqcnIndex
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.walk

/**
 * Phase 0 — deterministic project analysis. Outputs go to state/:
 *   build-model.json          gradle metadata (source roots, deps, AGP flag)
 *   source-inventory.json     every .java file with imports/types/deps
 *   convert-order.json        topological order over the SCC DAG
 *   risk-classification.json  LOW/MEDIUM/HIGH per file with reasons
 *
 * Conversion ordering: build a dependency graph between top-level classes,
 * contract cycles with Tarjan SCC, topologically sort the condensation.
 * Each batch is a list of source paths; single-element = normal file,
 * multi-element = cycle to convert together.
 *
 * Risk feeds only model selection (Haiku/Sonnet/Opus). The JetBrains skill
 * is the same regardless of tier.
 */

// ---- 0.1 Build model -----------------------------------------------------
//
// Gradle Tooling API would be cleaner but adds a heavy JVM dep (and a
// version-compatibility matrix against the target project's Gradle). For
// now we text-parse build.gradle / build.gradle.kts for the bits we need:
// the dependency block (configuration + coordinate, both preserved) and
// whether an AGP plugin is applied. Both consumers (phase 1 KMP scaffolding,
// phase 1 AGP9 skill gate) are tolerant of imperfect parsing.
//
// Configuration alternatives are ordered longest-first so e.g. `compileOnly`
// is matched before its prefix `compile` (\b doesn't help here because
// `\b` looks for a word↔non-word transition, and `compileOnly` is all
// word chars). The legacy `testCompile` / `testRuntime` / `compile` names
// were removed in Gradle 7 but appear in older real-world projects.
private const val CONFIG_NAMES =
    "testImplementation|testCompileOnly|testRuntimeOnly|testCompile|testRuntime|annotationProcessor|compileOnly|runtimeOnly|implementation|api|compile"

// Match `<config> "<coord>"` and `<config>("<coord>")` — Groovy DSL and
// the standard Kotlin DSL form.
private val depRegexBare = Regex("""\b($CONFIG_NAMES)\b[\s\(]+["']([^"'\s]+)["']""")

// Match `"<config>"("<coord>")` — the string-config form the orchestrator
// emits in generated build.gradle.kts files. Without this, re-running
// analyze on an already-scaffolded module finds no deps.
private val depRegexStringConfig =
    Regex("""["']($CONFIG_NAMES)["']\s*\(\s*["']([^"'\s]+)["']""")

// Match inter-module project deps: `implementation project(':foo')` (Groovy)
// or `implementation(project(":foo"))` (Kotlin DSL). The coordinate gets
// prefixed with `project:` so consumers can distinguish from Maven coords.
// Map-style deps (`group:, name:, version:`) and version-catalog deps
// (`libs.foo`) are not yet parsed — add as encountered.
private val depRegexProject =
    Regex("""\b($CONFIG_NAMES)\b\s*\(?\s*project\s*\(\s*["']([^"']+)["']\s*\)""")

private val agpPluginRegex = Regex("""\bid\s*\(?\s*["']com\.android\.""")

private fun readBuildFiles(projectRoot: Path, module: String): String {
    val modRoot = if (module.isNotBlank() && module != ".") projectRoot.resolve(module) else projectRoot
    val files = listOf("build.gradle.kts", "build.gradle").flatMap { name ->
        listOf(modRoot, projectRoot).map { it.resolve(name) }.filter { Files.exists(it) }
    }.distinct()
    return files.joinToString("\n") { Files.readString(it) }
}

fun extractBuildModel(cfg: Config): BuildModel {
    val root = cfg.projectRoot()
    val module = cfg.project.module
    val modRoot = if (module.isNotBlank() && module != ".") root.resolve(module) else root

    // Accept either the pre-scaffold (src/main/java) or post-scaffold KMP
    // layout (src/jvmMain/java). After phase 1 moves files, re-running
    // analyze should still find them.
    val mainCandidates = listOf("src/main/java", "src/jvmMain/java").map(modRoot::resolve)
    val testCandidates = listOf("src/test/java", "src/jvmTest/java").map(modRoot::resolve)
    val mainRoot = mainCandidates.firstOrNull { it.exists() }
        ?: error("no Java source root under $modRoot (tried: ${mainCandidates.joinToString(", ")})")
    val testRoot = testCandidates.firstOrNull { it.exists() }

    val text = readBuildFiles(root, module)
    val mavenLike = (depRegexBare.findAll(text) + depRegexStringConfig.findAll(text))
        .map { GradleDep(configuration = it.groupValues[1], coordinate = it.groupValues[2]) }
    val projectLike = depRegexProject.findAll(text)
        .map { GradleDep(configuration = it.groupValues[1], coordinate = "project:${it.groupValues[2]}") }
    val deps = (mavenLike + projectLike)
        .toSet()
        .sortedWith(compareBy({ it.configuration }, { it.coordinate }))
    val usesAgp = agpPluginRegex.containsMatchIn(text)

    return BuildModel(
        projectRoot = root.toString(),
        module = module,
        javaMainRoot = mainRoot.toString(),
        javaTestRoot = testRoot?.toString(),
        gradleDependencies = deps,
        pluginUsesAgp = usesAgp,
    )
}

// ---- 0.2 Source inventory -----------------------------------------------

private val parser = JavaParser(
    ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17)
)

private val typeNoise = setOf(
    "Override", "Deprecated", "SuppressWarnings", "FunctionalInterface",
    "Nullable", "NonNull", "NotNull",
)

private data class Parsed(
    val pkg: String,
    val typeNames: List<String>,
    val imports: List<String>,
    val refs: Set<String>,
)

private fun parseOne(path: Path): Parsed {
    val src = Files.readString(path)
    val result = parser.parse(src)
    val cu = result.result.orElse(null)
        ?: return parseRegex(src)  // graceful fallback if a single file doesn't parse

    val pkg = cu.packageDeclaration.map { it.nameAsString }.orElse("")
    val imports = cu.imports.map { imp ->
        imp.nameAsString + (if (imp.isAsterisk) ".*" else "")
    }
    val typeNames: List<String> = cu.types.mapNotNull { t: TypeDeclaration<*> -> t.nameAsString }
    val refs = mutableSetOf<String>()
    cu.walk { node ->
        // ClassOrInterfaceType, NameExpr, MarkerAnnotationExpr, etc. all
        // expose a string name we can interrogate.
        val name = runCatching {
            node.javaClass.getMethod("getNameAsString").invoke(node) as? String
        }.getOrNull()
        if (name != null && name.isNotEmpty() && name[0].isUpperCase() && name !in typeNoise) {
            refs.add(name)
        }
    }
    return Parsed(pkg, typeNames, imports, refs)
}

// Regex fallback for files that JavaParser chokes on (newer syntax, etc.).
private val rePackage = Regex("""^\s*package\s+([\w.]+)\s*;""", RegexOption.MULTILINE)
private val reImport = Regex("""^\s*import\s+(?:static\s+)?([\w.]+(?:\.\*)?)\s*;""", RegexOption.MULTILINE)
private val reTopType = Regex(
    """^\s*(?:public\s+|protected\s+|private\s+|abstract\s+|final\s+|sealed\s+|non-sealed\s+|static\s+|strictfp\s+)*(?:class|interface|enum|record|@interface)\s+([A-Z][A-Za-z0-9_]*)""",
    RegexOption.MULTILINE
)
private val reTypeRef = Regex("""\b([A-Z][A-Za-z0-9_]*)\b""")
private val reStripLine = Regex("""//[^\n]*""")
private val reStripBlock = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)

private fun parseRegex(src: String): Parsed {
    val stripped = src.replace(reStripBlock, "").replace(reStripLine, "")
    val pkg = rePackage.find(stripped)?.groupValues?.get(1) ?: ""
    val imports = reImport.findAll(stripped).map { it.groupValues[1] }.toList()
    val types = reTopType.findAll(stripped).map { it.groupValues[1] }.toList()
    val refs = reTypeRef.findAll(stripped).map { it.groupValues[1] }
        .filter { it !in typeNoise }
        .toSet()
    return Parsed(pkg, types, imports, refs)
}

@OptIn(kotlin.io.path.ExperimentalPathApi::class)
private fun walkJava(root: Path): List<Path> =
    if (!root.isDirectory()) emptyList()
    else root.walk().filter { it.toString().endsWith(".java") && Files.isRegularFile(it) }
        .sortedBy { it.toString() }
        .toList()

// ---- 0.4 Risk classification --------------------------------------------

private fun classifyRisk(unit: JavaSourceUnit, cfg: Config): Pair<String, List<String>> {
    var signals = 0
    val reasons = mutableListOf<String>()
    for (imp in unit.imports) {
        for (prefix in cfg.analysis.reflectionImports) {
            if (imp.startsWith(prefix)) {
                signals += 1
                reasons.add("reflection import: $imp")
                break
            }
        }
    }
    if (unit.typeNames.size > 1) {
        signals += 1
        reasons.add("multiple top-level types (${unit.typeNames.size})")
    }
    if (unit.dependsOn.size >= 8) {
        signals += 1
        reasons.add("high local dep count (${unit.dependsOn.size})")
    }
    return when {
        signals >= cfg.analysis.highThreshold -> "HIGH" to reasons
        signals >= cfg.analysis.mediumThreshold -> "MEDIUM" to reasons
        else -> "LOW" to reasons
    }
}

// ---- 0.3 Tarjan SCC + Kahn condensation topo sort -----------------------

private fun tarjanScc(nodes: List<String>, adj: Map<String, Set<String>>): List<List<String>> {
    var index = 0
    val stack = ArrayDeque<String>()
    val onStack = mutableSetOf<String>()
    val indices = mutableMapOf<String, Int>()
    val lowlink = mutableMapOf<String, Int>()
    val result = mutableListOf<List<String>>()

    fun strongConnect(start: String) {
        val workStack = ArrayDeque<Pair<String, Iterator<String>>>()
        indices[start] = index; lowlink[start] = index; index++
        stack.addFirst(start); onStack.add(start)
        workStack.addFirst(start to (adj[start] ?: emptySet()).iterator())

        while (workStack.isNotEmpty()) {
            val (node, it) = workStack.first()
            var advanced = false
            while (it.hasNext()) {
                val w = it.next()
                if (w !in indices) {
                    indices[w] = index; lowlink[w] = index; index++
                    stack.addFirst(w); onStack.add(w)
                    workStack.addFirst(w to (adj[w] ?: emptySet()).iterator())
                    advanced = true
                    break
                } else if (w in onStack) {
                    lowlink[node] = minOf(lowlink[node]!!, indices[w]!!)
                }
            }
            if (!advanced) {
                if (lowlink[node] == indices[node]) {
                    val scc = mutableListOf<String>()
                    while (true) {
                        val w = stack.removeFirst(); onStack.remove(w); scc.add(w)
                        if (w == node) break
                    }
                    result.add(scc)
                }
                workStack.removeFirst()
                if (workStack.isNotEmpty()) {
                    val parent = workStack.first().first
                    lowlink[parent] = minOf(lowlink[parent]!!, lowlink[node]!!)
                }
            }
        }
    }

    for (n in nodes) if (n !in indices) strongConnect(n)
    return result
}

/**
 * Topo-sort the SCC condensation in **conversion order**: each batch
 * appears after every batch it depends on.
 *
 * Built by inverting the natural dependency edges. `adj[u]` is "u depends
 * on v", so the raw DAG has edges `depender → dep`. Kahn's algorithm
 * starting from `indeg==0` would yield ROOTS of that DAG — the top-level
 * types nothing depends on — first, which is exactly backwards for
 * build/conversion order. We want LEAVES (nothing further to wait for)
 * first.
 *
 * Solution: build `condAdj` in the inverted direction (`dep → depender`)
 * so that "indeg of x" means "number of x's own dependencies." Then
 * `indeg==0` are the SCCs with no outgoing deps — the leaves we want
 * first.
 */
private fun topoSort(sccs: List<List<String>>, adj: Map<String, Set<String>>): List<List<String>> {
    val nodeToScc = mutableMapOf<String, Int>()
    for ((i, comp) in sccs.withIndex()) for (n in comp) nodeToScc[n] = i

    // `condAdj[b]` = SCCs that depend on b. When we process b, we
    // decrement those dependers' indeg, freeing them up.
    // `indeg[a]` = number of inter-SCC dependencies a still has waiting.
    val condAdj = (0 until sccs.size).associateWith { mutableSetOf<Int>() }
    val indeg = (0 until sccs.size).associateWith { 0 }.toMutableMap()
    for ((u, outs) in adj) for (v in outs) {
        val a = nodeToScc[u]!!; val b = nodeToScc[v]!!
        // u depends on v ⇒ a depends on b. Record the inverse edge
        // (b → a) for Kahn so we can release a once b is done.
        if (a != b && a !in condAdj[b]!!) {
            condAdj[b]!!.add(a); indeg[a] = indeg[a]!! + 1
        }
    }
    val ready = ArrayDeque(
        indeg.filterValues { it == 0 }.keys.sortedBy { sccs[it].min() }
    )
    val order = mutableListOf<List<String>>()
    while (ready.isNotEmpty()) {
        val i = ready.removeFirst()
        order.add(sccs[i].sorted())
        for (j in condAdj[i]!!.sorted()) {
            indeg[j] = indeg[j]!! - 1
            if (indeg[j] == 0) ready.addLast(j)
        }
        // Re-sort ready by smallest filename for determinism.
        val resorted = ready.sortedBy { sccs[it].min() }
        ready.clear(); ready.addAll(resorted)
    }
    return order
}

// ---- Driver -------------------------------------------------------------

fun analyze(cfg: Config): AnalysisResult {
    val build = extractBuildModel(cfg)
    val mainRoot = Path.of(build.javaMainRoot)
    val testRoot = build.javaTestRoot?.let { Path.of(it) }

    val rawUnits = mutableListOf<Pair<JavaSourceUnit, Set<String>>>()
    for ((isTest, root) in listOf(false to mainRoot, true to testRoot)) {
        if (root == null || !root.exists()) continue
        for (path in walkJava(root)) {
            val parsed = parseOne(path)
            val primary = parsed.typeNames.firstOrNull() ?: path.fileName.toString().removeSuffix(".java")
            val fqcn = if (parsed.pkg.isNotEmpty()) "${parsed.pkg}.$primary" else primary
            val rel = root.relativize(path).toString()
            val unit = JavaSourceUnit(
                sourcePath = path.toString(),
                relativePath = rel,
                `package` = parsed.pkg,
                fqcn = fqcn,
                typeNames = parsed.typeNames,
                isTest = isTest,
                imports = parsed.imports,
            )
            rawUnits.add(unit to parsed.refs)
        }
    }

    // Resolve refs to in-project deps.
    val idx = fqcnIndex(rawUnits.map { it.first })
    val samePkg = rawUnits.map { it.first }.groupBy { it.`package` }
    val resolvedUnits = rawUnits.map { (u, refs) ->
        val deps = sortedSetOf<String>()
        for (ref in refs) {
            if (ref in u.typeNames) continue
            val byImport = u.imports.firstOrNull { it.endsWith(".$ref") }?.let { idx[it] }
            val resolved = byImport ?: samePkg[u.`package`]?.firstOrNull {
                ref in it.typeNames && it !== u
            }
            if (resolved != null && resolved !== u) deps.add(resolved.sourcePath)
        }
        u.copy(dependsOn = deps.toList())
    }

    val nodes = resolvedUnits.map { it.sourcePath }
    val adj = resolvedUnits.associate { it.sourcePath to it.dependsOn.toSet() }
    val sccs = tarjanScc(nodes, adj)
    val order = topoSort(sccs, adj)
    val cycles = order.filter { it.size > 1 }

    val withRisk = resolvedUnits.map { u ->
        val (risk, reasons) = classifyRisk(u, cfg)
        u.copy(risk = risk, riskReasons = reasons)
    }

    return AnalysisResult(buildModel = build, units = withRisk, order = order, cycles = cycles)
}

fun runAnalyze(cfg: Config): AnalysisResult {
    println("[analyze] project_root=${cfg.projectRoot()}")
    val res = analyze(cfg)
    res.write(cfg.stateDir())
    val risk = res.units.groupingBy { it.risk }.eachCount()
    println("[analyze] java units: ${res.units.size} | batches: ${res.order.size} | cycles: ${res.cycles.size}")
    println("[analyze] risk: LOW=${risk["LOW"] ?: 0} MEDIUM=${risk["MEDIUM"] ?: 0} HIGH=${risk["HIGH"] ?: 0}")
    if (res.cycles.isNotEmpty()) {
        println("[analyze] cycles:")
        for (c in res.cycles) {
            val names = c.joinToString(", ") { Path.of(it).fileName.toString() }
            println("          (${c.size}) $names")
        }
    }
    return res
}
