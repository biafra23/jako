package jako.runners

import jako.Config
import java.nio.file.Files
import java.nio.file.Path

/**
 * Per-file backend chain step 2 — local OpenAI-compatible endpoint
 * (LM Studio / Ollama / vLLM / llama.cpp). Used only for LOW-risk files
 * when `local_model.enabled = true` and the run-start probe succeeded.
 *
 * Same JetBrains skill as system prompt; the model is asked to return the
 * full .kt inside a fenced ```kotlin block, which we extract and write.
 */

private fun apiKey(cfg: Config): String? {
    val env = cfg.localModel.apiKeyEnv
    if (env.isBlank()) return null
    return System.getenv(env)
}

private fun buildMessages(skillText: String, javaFile: Path, ktFile: Path, isTest: Boolean, extra: String): List<ChatMessage> {
    val javaSrc = if (Files.exists(javaFile)) Files.readString(javaFile) else ""
    val ktSrc = if (Files.exists(ktFile)) Files.readString(ktFile) else ""
    val user = buildString {
        appendLine("Refine the Kotlin file (currently at ${ktFile.fileName}) using the JetBrains java-to-kotlin skill conventions.")
        appendLine()
        if (isTest) {
            appendLine("Test-conversion constraints:")
            appendLine("  - This is a test file. Production code in src/jvmMain/kotlin is already Kotlin — call it idiomatically (property syntax for Java-style getters, named/default args).")
            appendLine("  - Keep the test-framework annotations the original used (@Test, @BeforeEach, @ParameterizedTest, @ValueSource, @MethodSource, @DisplayName, etc.) — they work the same in Kotlin.")
            appendLine("  - Preserve test semantics exactly: same assertions, same parametrization, same fixture setup.")
            appendLine("  - Prefer kotlin.test assertions (assertEquals, assertTrue, assertFailsWith) when the rewrite from JUnit's Assertions.* is mechanical; otherwise leave the JUnit assertion alone.")
            appendLine("  - No new external dependencies.")
        } else {
            appendLine("Constraints:")
            appendLine("  - Public API must remain Java-callable; Java tests in src/jvmTest/java compile against this file.")
            appendLine("  - Add @JvmStatic / @JvmField / @JvmOverloads / @JvmName as needed for interop.")
            appendLine("  - No new external dependencies.")
        }
        appendLine("  - Return the FULL final .kt file, nothing else, inside a single ```kotlin code block.")
        appendLine("  - Do not deliberate or print reasoning — output the code block and stop.")
        appendLine()
        appendLine("=== Original Java (${javaFile.fileName}) ===")
        appendLine(javaSrc)
        appendLine()
        appendLine("=== Current Kotlin draft (${ktFile.fileName}) ===")
        appendLine(ktSrc)
        if (extra.isNotBlank()) {
            appendLine()
            appendLine("=== Additional context ===")
            appendLine(extra)
        }
    }
    return listOf(
        ChatMessage(role = "system", content = skillText),
        ChatMessage(role = "user", content = user),
    )
}

/** Probe the local endpoint at run start. */
fun probeLocalLlm(cfg: Config): Boolean {
    if (!cfg.localModel.enabled) return false
    return probeEndpoint(cfg.localModel.baseUrl, apiKey(cfg), cfg.localModel.probeTimeoutSeconds)
}

fun invokeLocalLlm(
    cfg: Config,
    skillPath: Path,
    javaFile: Path,
    ktFile: Path,
    isTest: Boolean = false,
    extraUserPrompt: String = "",
): SkillResult {
    if (!Files.exists(skillPath)) {
        error("skill file not found at $skillPath")
    }
    val skill = Files.readString(skillPath)
    val messages = buildMessages(skill, javaFile, ktFile, isTest, extraUserPrompt)

    val t0 = System.currentTimeMillis()
    val (status, body) = postChat(
        baseUrl = cfg.localModel.baseUrl,
        apiKey = apiKey(cfg),
        model = cfg.localModel.model,
        messages = messages,
        temperature = cfg.localModel.temperature,
        maxTokens = cfg.localModel.maxTokens,
        timeoutSeconds = cfg.localModel.timeoutSeconds,
    )
    val elapsed = (System.currentTimeMillis() - t0) / 1000.0
    val modelLabel = "local:${cfg.localModel.model}"

    if (status >= 400) {
        return SkillResult(
            ok = false, model = modelLabel, elapsedSeconds = elapsed,
            stdoutTail = body.takeLast(2000),
            stderrTail = "HTTP $status",
        )
    }

    val parsed = runCatching { httpJson.decodeFromString(ChatResponse.serializer(), body) }.getOrNull()
        ?: return SkillResult(
            ok = false, model = modelLabel, elapsedSeconds = elapsed,
            stdoutTail = body.takeLast(2000),
            stderrTail = "non-JSON response",
        )

    val content = parsed.choices.firstOrNull()?.message?.content
        ?: return SkillResult(
            ok = false, model = modelLabel, elapsedSeconds = elapsed,
            stdoutTail = body.takeLast(2000),
            stderrTail = "unexpected response shape",
        )

    val kotlin = extractKotlin(content) ?: return SkillResult(
        ok = false, model = modelLabel, elapsedSeconds = elapsed,
        stdoutTail = content.takeLast(2000),
        stderrTail = "no ```kotlin block in response",
    )

    Files.createDirectories(ktFile.parent)
    Files.writeString(ktFile, kotlin)
    return SkillResult(
        ok = true, model = modelLabel, elapsedSeconds = elapsed,
        stdoutTail = content.takeLast(2000),
    )
}
