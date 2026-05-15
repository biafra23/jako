package jako

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Resumable per-unit run state. Persisted to state/run-state.json after
 * every successful phase transition so an interrupted run resumes from the
 * last green file.
 *
 * Phase-2 lifecycle:
 *   pending    -> not started
 *   j2k_done   -> mechanical first pass written to .kt
 *   refined    -> JetBrains-skill idiomatic pass applied
 *   verified   -> ./gradlew compileKotlinJvm + test green
 *   committed  -> git commit recorded
 *   failed     -> three strikes; manual-review
 */
@Serializable
data class UnitState(
    val sourcePath: String,
    var status: String = "pending",
    var ktPath: String? = null,
    var retryCount: Int = 0,
    var modelUsed: String? = null,
    var lastError: String? = null,
    var updatedAtMillis: Long = 0L,
    val notes: MutableList<String> = mutableListOf(),
)

@Serializable
private data class RunStatePayload(val units: Map<String, UnitState>)

class RunState(
    val units: MutableMap<String, UnitState> = mutableMapOf(),
    var path: Path? = null,
) {
    fun save() {
        val p = path ?: return
        Files.createDirectories(p.parent)
        val tmp = p.resolveSibling(p.fileName.toString() + ".tmp")
        Files.writeString(tmp, json.encodeToString(RunStatePayload(units)))
        Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    fun getOrCreate(sourcePath: String): UnitState =
        units.getOrPut(sourcePath) { UnitState(sourcePath) }

    fun mark(
        sourcePath: String,
        status: String? = null,
        ktPath: String? = null,
        modelUsed: String? = null,
        lastError: String? = null,
        incrementRetry: Boolean = false,
        addNote: String? = null,
    ): UnitState {
        val st = getOrCreate(sourcePath)
        if (status != null) st.status = status
        if (ktPath != null) st.ktPath = ktPath
        if (modelUsed != null) st.modelUsed = modelUsed
        if (lastError != null) st.lastError = lastError
        if (incrementRetry) st.retryCount += 1
        if (addNote != null) st.notes.add(addNote)
        st.updatedAtMillis = System.currentTimeMillis()
        return st
    }

    companion object {
        fun load(path: Path): RunState {
            if (!Files.exists(path)) return RunState(path = path)
            val payload = json.decodeFromString(
                RunStatePayload.serializer(), Files.readString(path)
            )
            return RunState(payload.units.toMutableMap(), path)
        }
    }
}
