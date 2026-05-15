package jako

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolute

/**
 * Top-level YAML config. snake_case in the file, camelCase in Kotlin; the
 * @SerialName annotations bridge the two.
 *
 * `base` is set after loading to the directory the YAML lives in so relative
 * paths inside the file (e.g. `scripts/passthrough-j2k.sh`) resolve against
 * the config's location rather than the JVM's working directory.
 */
@Serializable
data class Config(
    val project: ProjectCfg = ProjectCfg(),
    val claude: ClaudeCfg = ClaudeCfg(),
    val j2k: J2KCfg = J2KCfg(),
    val skills: SkillsCfg = SkillsCfg(),
    val gradle: GradleCfg = GradleCfg(),
    val analysis: AnalysisCfg = AnalysisCfg(),
    val verify: VerifyCfg = VerifyCfg(),
    val git: GitCfg = GitCfg(),
    @SerialName("local_model") val localModel: LocalModelCfg = LocalModelCfg(),
    val fallback: FallbackCfg = FallbackCfg(),
    val state: StateCfg = StateCfg(),
) {
    @kotlinx.serialization.Transient
    var base: Path = Path.of(".").toAbsolutePath().normalize()

    fun resolve(raw: String): Path {
        val expanded = if (raw.startsWith("~")) {
            Path.of(System.getProperty("user.home") + raw.removePrefix("~"))
        } else {
            Path.of(raw)
        }
        return if (expanded.isAbsolute()) expanded.normalize()
        else base.resolve(expanded).normalize()
    }

    fun stateDir(): Path = resolve(state.dir)
    fun projectRoot(): Path = resolve(project.root)
    fun skillPath(name: String): Path = resolve(skills.root).resolve(name)
}

@Serializable
data class ProjectCfg(
    val root: String = "",
    val module: String = "",
)

@Serializable
data class ClaudeCfg(
    val cli: String = "",
    @SerialName("default_model") val defaultModel: String = "claude-sonnet-4-6",
    val models: Map<String, String> = mapOf(
        "LOW" to "claude-haiku-4-5-20251001",
        "MEDIUM" to "claude-sonnet-4-6",
        "HIGH" to "claude-opus-4-7",
    ),
    @SerialName("max_turns") val maxTurns: Int = 3,
    @SerialName("permission_mode") val permissionMode: String = "acceptEdits",
    @SerialName("extra_args") val extraArgs: List<String> = emptyList(),
    @SerialName("pause_on_rate_limit") val pauseOnRateLimit: Boolean = true,
)

@Serializable
data class J2KCfg(
    /** "external" | "headless_idea" | "passthrough". */
    val strategy: String = "passthrough",
    val command: String = "",
    val args: List<String> = emptyList(),
    @SerialName("timeout_seconds") val timeoutSeconds: Long = 1800,
)

@Serializable
data class SkillsCfg(
    val root: String = "vendored-skills/upstream/skills",
    @SerialName("java_to_kotlin") val javaToKotlin: String = "kotlin-tooling-java-to-kotlin/SKILL.md",
    @SerialName("agp9_migration") val agp9Migration: String = "kotlin-tooling-agp9-migration/SKILL.md",
)

@Serializable
data class GradleCfg(
    val wrapper: String = "./gradlew",
    @SerialName("test_command") val testCommand: String = ":{module}:compileKotlinJvm :{module}:test",
    @SerialName("timeout_seconds") val timeoutSeconds: Long = 1800,
)

@Serializable
data class AnalysisCfg(
    val parser: String = "javaparser",
    @SerialName("separate_tests") val separateTests: Boolean = true,
    @SerialName("reflection_imports") val reflectionImports: List<String> = listOf(
        "java.lang.reflect", "sun.misc.Unsafe",
    ),
    @SerialName("high_threshold") val highThreshold: Int = 3,
    @SerialName("medium_threshold") val mediumThreshold: Int = 1,
)

@Serializable
data class VerifyCfg(
    @SerialName("max_retries") val maxRetries: Int = 3,
)

@Serializable
data class GitCfg(
    @SerialName("commit_per_file") val commitPerFile: Boolean = true,
    val author: String = "",
)

/**
 * Local OpenAI-compatible endpoint (LM Studio / Ollama / vLLM / llama.cpp).
 * Used only for LOW-risk files when enabled = true and the reachability
 * probe at phase-2 start succeeds.
 */
@Serializable
data class LocalModelCfg(
    val enabled: Boolean = false,
    @SerialName("base_url") val baseUrl: String = "http://localhost:1234/v1",
    val model: String = "google/gemma-4-31b",
    @SerialName("api_key_env") val apiKeyEnv: String = "",
    val temperature: Double = 0.1,
    @SerialName("max_tokens") val maxTokens: Int = 16384,
    @SerialName("timeout_seconds") val timeoutSeconds: Long = 1800,
    @SerialName("probe_timeout_seconds") val probeTimeoutSeconds: Long = 3,
)

@Serializable
data class DeepSeekCfg(
    @SerialName("base_url") val baseUrl: String = "https://api.deepseek.com",
    @SerialName("api_key_env") val apiKeyEnv: String = "DEEPSEEK_API_KEY",
    val model: String = "deepseek-chat",
    val temperature: Double = 0.1,
    @SerialName("max_tokens") val maxTokens: Int = 16384,
    @SerialName("timeout_seconds") val timeoutSeconds: Long = 600,
)

@Serializable
data class FallbackCfg(
    val enabled: Boolean = false,
    @SerialName("return_to_claude_after_window") val returnToClaudeAfterWindow: Boolean = true,
    val backend: String = "deepseek",
    val deepseek: DeepSeekCfg = DeepSeekCfg(),
)

@Serializable
data class StateCfg(
    val dir: String = "state",
)

// ----- loader ------------------------------------------------------------

private val yaml = Yaml(
    configuration = YamlConfiguration(
        strictMode = false,
        encodeDefaults = true,
    )
)

fun loadConfig(path: Path): Config {
    val text = Files.readString(path)
    val cfg = yaml.decodeFromString(Config.serializer(), text)
    cfg.base = path.absolute().parent.normalize()
    return cfg
}
