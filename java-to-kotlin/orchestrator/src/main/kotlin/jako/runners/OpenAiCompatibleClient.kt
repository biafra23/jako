package jako.runners

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Shared OpenAI-compatible /v1/chat/completions client used by both
 * `LocalLlmBackend` (LM Studio / Ollama / vLLM / llama.cpp) and
 * `DeepSeekBackend`. Tiny stdlib-only wrapper around java.net.http; no
 * extra dep, no streaming, single-shot completions.
 */

@Serializable
data class ChatMessage(val role: String, val content: String)

@Serializable
private data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double,
    val max_tokens: Int,
    val stream: Boolean = false,
)

@Serializable
data class ChatResponse(
    val id: String? = null,
    val choices: List<Choice> = emptyList(),
)

@Serializable
data class Choice(val index: Int = 0, val message: ChatMessage? = null)

internal val httpJson = Json { ignoreUnknownKeys = true; encodeDefaults = false }

/** Returns (httpStatus, body). Throws only on network-level errors. */
fun postChat(
    baseUrl: String,
    apiKey: String?,
    model: String,
    messages: List<ChatMessage>,
    temperature: Double,
    maxTokens: Int,
    timeoutSeconds: Long,
): Pair<Int, String> {
    val req = HttpRequest.newBuilder()
        .uri(URI.create(baseUrl.trimEnd('/') + "/chat/completions"))
        .timeout(Duration.ofSeconds(timeoutSeconds))
        .header("Content-Type", "application/json")
        .apply { if (!apiKey.isNullOrBlank()) header("Authorization", "Bearer $apiKey") }
        .POST(
            HttpRequest.BodyPublishers.ofString(
                httpJson.encodeToString(
                    ChatRequest(
                        model = model,
                        messages = messages,
                        temperature = temperature,
                        max_tokens = maxTokens,
                    )
                )
            )
        )
        .build()
    val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()
    val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
    return resp.statusCode() to resp.body()
}

/**
 * Best-effort liveness probe for an OpenAI-compatible endpoint. GETs
 * /models (some servers serve it at the base URL, some at /v1/models).
 * Returns true if any URL responds with 2xx within the timeout.
 */
fun probeEndpoint(baseUrl: String, apiKey: String?, timeoutSeconds: Long): Boolean {
    val urls = listOf(
        baseUrl.trimEnd('/') + "/models",
        baseUrl.trimEnd('/').removeSuffix("/v1") + "/v1/models",
    ).distinct()
    val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(timeoutSeconds))
        .build()
    for (u in urls) {
        runCatching {
            val req = HttpRequest.newBuilder()
                .uri(URI.create(u))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .apply { if (!apiKey.isNullOrBlank()) header("Authorization", "Bearer $apiKey") }
                .GET()
                .build()
            val resp = client.send(req, HttpResponse.BodyHandlers.discarding())
            if (resp.statusCode() in 200..299) return true
        }
    }
    return false
}

private val fencePattern = Regex(
    """```(?:kotlin|kt)?\s*\n(.*?)```""",
    setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
)

/**
 * Pull the Kotlin source out of a model response. Accepts a ```kotlin block
 * OR a bare response that looks like Kotlin (starts with package/import/etc).
 */
fun extractKotlin(text: String): String? {
    fencePattern.find(text)?.let { return it.groupValues[1].trim() + "\n" }
    val t = text.trim()
    val kotlinishStarts = listOf("package ", "import ", "@file:", "fun ", "class ", "object ")
    if (kotlinishStarts.any { t.startsWith(it) }) return t + "\n"
    return null
}
