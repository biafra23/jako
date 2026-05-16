package jako.runners

import jako.Config
import java.nio.file.Files
import java.nio.file.Path

/**
 * Per-file backend chain step 4 — DeepSeek v4 fallback for when the Claude
 * 5-hour subscription window is exhausted. OpenAI-compatible API; we reuse
 * `OpenAiCompatibleClient` for HTTP and code-block extraction.
 */

private fun apiKey(cfg: Config): String {
    val env = cfg.fallback.deepseek.apiKeyEnv
    val key = System.getenv(env).orEmpty()
    if (key.isBlank()) {
        error("DeepSeek API key env var '$env' is empty. " +
            "Export it before running, or set fallback.enabled=false.")
    }
    return key
}

private fun buildMessages(skillText: String, javaFile: Path, ktFile: Path, isTest: Boolean, extra: String): List<ChatMessage> {
    val javaSrc = if (Files.exists(javaFile)) Files.readString(javaFile) else ""
    val ktSrc = if (Files.exists(ktFile)) Files.readString(ktFile) else ""
    val user = buildString {
        appendLine("Refine the Kotlin file (currently at ${ktFile.fileName}) using the JetBrains java-to-kotlin skill conventions.")
        appendLine()
        appendLine(refineConstraintsBlock(isTest))
        // Same fenced-code-block output instructions as the local-LLM
        // backend — DeepSeek is invoked via the same OpenAI-compatible
        // path, and the chat client extracts the .kt from a single
        // ```kotlin block.
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

fun invokeDeepSeek(
    cfg: Config,
    skillPath: Path,
    javaFile: Path,
    ktFile: Path,
    isTest: Boolean = false,
    extraUserPrompt: String = "",
): SkillResult {
    if (!Files.exists(skillPath)) {
        error("skill file not found at $skillPath. Vendor Kotlin/kotlin-agent-skills " +
            "before enabling DeepSeek fallback.")
    }
    val skill = Files.readString(skillPath)
    val messages = buildMessages(skill, javaFile, ktFile, isTest, extraUserPrompt)

    val t0 = System.currentTimeMillis()
    val (status, body) = postChat(
        baseUrl = cfg.fallback.deepseek.baseUrl,
        apiKey = apiKey(cfg),
        model = cfg.fallback.deepseek.model,
        messages = messages,
        temperature = cfg.fallback.deepseek.temperature,
        maxTokens = cfg.fallback.deepseek.maxTokens,
        timeoutSeconds = cfg.fallback.deepseek.timeoutSeconds,
    )
    val elapsed = (System.currentTimeMillis() - t0) / 1000.0
    val modelLabel = "deepseek:${cfg.fallback.deepseek.model}"

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
