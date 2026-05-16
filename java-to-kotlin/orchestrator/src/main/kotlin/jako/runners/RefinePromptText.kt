package jako.runners

/**
 * Shared prompt fragments embedded by every refine backend (Claude,
 * Local LLM, DeepSeek). Keeping the wording in one place stops the
 * three backend prompts from drifting — three identical constraint
 * blocks copy-pasted three ways is exactly how the test-mode wording
 * ended up subtly different between backends in the original Phase 4
 * change.
 *
 * Backend-specific framing (scope blocks, "return inside a ```kotlin
 * code block", JSON-output instructions, the file-content embedding)
 * still lives in each backend, since those parts genuinely differ.
 */

/**
 * The constraint bullet list inserted into every refine prompt. Same
 * substance across all three backends so the model gets the same
 * guidance regardless of who's calling. Trailing newline omitted —
 * the caller appends.
 */
fun refineConstraintsBlock(isTest: Boolean): String = if (isTest) {
    """
    |Test-conversion constraints (apply to the refined .kt only):
    |  - This is a test file. Production code in src/jvmMain/kotlin is already Kotlin — call it idiomatically (property syntax for Java-style getters, named/default args, no manual boxing of primitives).
    |  - Keep the test-framework annotations the original used (@Test, @BeforeEach, @AfterEach, @ParameterizedTest, @ValueSource, @MethodSource, @DisplayName, etc.) — they work the same in Kotlin.
    |  - Preserve test semantics exactly: same assertions, same parametrization, same fixture setup, same failure cases.
    |  - For plain-shape assertions (equality, truthiness, exception expectations) prefer the kotlin.test API (assertEquals, assertTrue, assertFailsWith) when the original used JUnit's Assertions.* — but only when the rewrite is mechanical. If a JUnit-specific assertion (assertThrows with executable, Hamcrest, AssertJ) doesn't have a clean Kotlin-test equivalent, leave it alone.
    |  - Do not introduce new external dependencies.
    """.trimMargin()
} else {
    """
    |Interop constraints (apply to the refined .kt only):
    |  - Public API must remain Java-callable; Java tests in src/jvmTest/java compile against this file.
    |  - Add @JvmStatic / @JvmField / @JvmOverloads / @JvmName as needed for interop.
    |  - Do not introduce new external dependencies.
    """.trimMargin()
}
