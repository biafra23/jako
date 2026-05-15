package jako

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.nio.file.Files

@Serializable
private data class ReportJson(
    val totals: Totals,
    val statusCounts: Map<String, Int>,
    val riskCounts: Map<String, Int>,
    val modelCounts: Map<String, Int>,
    val wallSeconds: Double? = null,
    val failed: List<FailedEntry>,
)

@Serializable
private data class Totals(
    val javaUnits: Int,
    val batches: Int,
    val cycles: Int,
)

@Serializable
private data class FailedEntry(
    val sourcePath: String,
    val relativePath: String?,
    val retryCount: Int,
    val lastError: String?,
    val notes: List<String>,
)

fun writeReports(cfg: Config, analysis: AnalysisResult, state: RunState, wallMillis: Long?) {
    val statusCounts = mutableMapOf<String, Int>()
    val riskCounts = mutableMapOf<String, Int>()
    val modelCounts = mutableMapOf<String, Int>()
    val byPath = analysis.units.associateBy { it.sourcePath }
    val failed = mutableListOf<FailedEntry>()

    for ((path, ust) in state.units) {
        statusCounts.merge(ust.status, 1, Int::plus)
        ust.modelUsed?.let { modelCounts.merge(it, 1, Int::plus) }
        byPath[path]?.let { riskCounts.merge(it.risk, 1, Int::plus) }
        if (ust.status == "failed") {
            failed.add(
                FailedEntry(
                    sourcePath = path,
                    relativePath = byPath[path]?.relativePath,
                    retryCount = ust.retryCount,
                    lastError = ust.lastError?.take(1000),
                    notes = ust.notes.toList(),
                )
            )
        }
    }

    val stateDir = cfg.stateDir()
    Files.createDirectories(stateDir)
    val report = ReportJson(
        totals = Totals(
            javaUnits = analysis.units.size,
            batches = analysis.order.size,
            cycles = analysis.cycles.size,
        ),
        statusCounts = statusCounts,
        riskCounts = riskCounts,
        modelCounts = modelCounts,
        wallSeconds = wallMillis?.let { it / 1000.0 },
        failed = failed,
    )
    Files.writeString(stateDir.resolve("report.json"), json.encodeToString(report))

    val md = buildString {
        appendLine("# Conversion report")
        appendLine()
        appendLine("- Java units found: **${analysis.units.size}**")
        appendLine("- Conversion batches: **${analysis.order.size}** (cycles: ${analysis.cycles.size})")
        wallMillis?.let { appendLine("- Wall clock: **${"%.1f".format(it / 1000.0)}s**") }
        appendLine()
        appendLine("## Status")
        appendLine()
        statusCounts.toSortedMap().forEach { (s, n) -> appendLine("- $s: $n") }
        appendLine()
        appendLine("## Risk")
        appendLine()
        riskCounts.toSortedMap().forEach { (r, n) -> appendLine("- $r: $n") }
        appendLine()
        appendLine("## Models used")
        appendLine()
        modelCounts.toSortedMap().forEach { (m, n) -> appendLine("- $m: $n") }
        if (failed.isNotEmpty()) {
            appendLine()
            appendLine("## Failed units (manual review)")
            appendLine()
            failed.forEach { f ->
                val firstLine = f.lastError?.lineSequence()?.firstOrNull().orEmpty()
                appendLine("- `${f.relativePath}` (retries=${f.retryCount}): $firstLine")
            }
        }
    }
    Files.writeString(stateDir.resolve("report.md"), md)
    println("[report] wrote ${stateDir.resolve("report.json")} and ${stateDir.resolve("report.md")}")
}
