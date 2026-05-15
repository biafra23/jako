package jako

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

/**
 * Plain serialisable dataclasses shared across phases. These are the on-disk
 * schemas under `state/` as well — phase 0 writes them, phase 1 and phase 2
 * read them back.
 */

@Serializable
data class JavaSourceUnit(
    val sourcePath: String,
    val relativePath: String,
    val `package`: String,
    val fqcn: String,
    val typeNames: List<String>,
    val isTest: Boolean,
    val imports: List<String> = emptyList(),
    val dependsOn: List<String> = emptyList(),
    val risk: String = "MEDIUM",
    val riskReasons: List<String> = emptyList(),
)

@Serializable
data class GradleDep(
    /** Original Gradle configuration the dep was declared in: api,
     *  implementation, compileOnly, runtimeOnly, testImplementation,
     *  testCompileOnly, testRuntimeOnly, annotationProcessor, or the
     *  legacy `compile`. */
    val configuration: String,
    val coordinate: String,
)

@Serializable
data class BuildModel(
    val projectRoot: String,
    val module: String,
    val javaMainRoot: String,
    val javaTestRoot: String? = null,
    val gradleDependencies: List<GradleDep> = emptyList(),
    val pluginUsesAgp: Boolean = false,
)

/**
 * Phase 0 produces this aggregate and persists each field to its own JSON
 * file under state/ for human inspection and resumability.
 */
data class AnalysisResult(
    val buildModel: BuildModel,
    val units: List<JavaSourceUnit>,
    /** Batches of source paths. Single-element batches are normal files;
     *  multi-element batches are cycles converted together. */
    val order: List<List<String>>,
    val cycles: List<List<String>>,
) {
    fun write(stateDir: Path) {
        Files.createDirectories(stateDir)
        stateDir.resolve("build-model.json").write(buildModel)
        stateDir.resolve("source-inventory.json").write(units)
        stateDir.resolve("convert-order.json").write(order)
        stateDir.resolve("risk-classification.json").write(
            units.associate {
                it.sourcePath to RiskEntry(it.risk, it.riskReasons)
            }
        )
    }

    companion object {
        fun read(stateDir: Path): AnalysisResult {
            val bm = stateDir.resolve("build-model.json").read<BuildModel>()
            val units = stateDir.resolve("source-inventory.json").read<List<JavaSourceUnit>>()
            val order = stateDir.resolve("convert-order.json").read<List<List<String>>>()
            val cycles = order.filter { it.size > 1 }
            return AnalysisResult(bm, units, order, cycles)
        }
    }
}

@Serializable
data class RiskEntry(val risk: String, val reasons: List<String>)

/**
 * Build an index of fully-qualified class name -> unit. Used by phase 0
 * to resolve type references against in-project sources.
 */
fun fqcnIndex(units: List<JavaSourceUnit>): Map<String, JavaSourceUnit> {
    val idx = mutableMapOf<String, JavaSourceUnit>()
    for (u in units) {
        for (t in u.typeNames) {
            val key = if (u.`package`.isNotEmpty()) "${u.`package`}.$t" else t
            idx[key] = u
        }
    }
    return idx
}

// ----- JSON helpers ------------------------------------------------------

internal val json = Json {
    prettyPrint = true
    encodeDefaults = true
    explicitNulls = false
    classDiscriminator = "_type"
}

internal inline fun <reified T> Path.write(value: T) {
    Files.writeString(this, json.encodeToString(value))
}

internal inline fun <reified T> Path.read(): T =
    json.decodeFromString(Files.readString(this))
