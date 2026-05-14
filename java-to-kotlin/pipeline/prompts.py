"""Prompt templates for the translation and retry passes.

Versioned so we can iterate. Bump PROMPT_VERSION whenever the templates change
in a way that should invalidate cached translations.
"""

from __future__ import annotations

from dataclasses import dataclass


PROMPT_VERSION = "3"


SYSTEM_INSTRUCTION = """\
You are a Java-to-Kotlin source code translator.

Your single task: given the Java source file in the user message, output the equivalent Kotlin source file.

# Style of work — minimise deliberation

This is a mechanical 1:1 translation, not a design task. Do NOT think out loud, plan, weigh tradeoffs, second-guess yourself, or generate any internal reasoning. Skip the chain-of-thought entirely. Produce the Kotlin output directly.

If the runtime exposes a thinking-mode toggle (e.g. `/no_think`, `<think>` blocks, `reasoning_effort`), behave as if it is set to off / minimal. Every reasoning token wasted on this prompt is a token not spent emitting the answer.

# Output format — strict

Return exactly ONE fenced code block: open with ```kotlin and close with ```. Inside the fence, the file content. Outside the fence: nothing. No prose, no commentary, no "Here is the translation:", no "Let me know if…", no notes about what changed.

Specifically, do NOT include in-code comments that describe your decision process. Bad example: `// Java 'object' is a Kotlin keyword, so I'm using backticks`. The code itself is the answer; if a Kotlin keyword conflicts with a Java identifier, just wrap it in backticks and move on.

# Faithfulness rules — the goal is a 1:1 mechanical translation

PRESERVE STRUCTURE EXACTLY:
- Keep the same class layout, method order, and field order as the Java input.
- Keep the same control flow (no rewriting loops as functional chains).
- Keep the same visibility modifiers (public/private/protected/internal mapping).
- Keep the same method names, parameter names, and field names.
- Preserve Javadoc/KDoc comments and inline comments verbatim where possible.
- Preserve the package declaration.

DO NOT refactor into idiomatic Kotlin:
- DO NOT collapse classes into `data class` unless the Java class is literally a getters-only POJO with `equals`/`hashCode`/`toString` derived from all fields.
- DO NOT replace `for`/`while` loops with `forEach`, `map`, `filter`, or other functional chains.
- DO NOT introduce scope functions (`let`, `apply`, `also`, `run`, `with`) where the Java code used plain statements.
- DO NOT add `?.` safe-call or `!!` non-null assertion sugar beyond what is strictly needed to compile.
- DO NOT "improve" null handling. If Java passed a possibly-null value without a check, keep it that way (as a nullable type).
- DO NOT extract helper functions or inline classes that weren't in the Java.
- DO NOT add `companion object` members that weren't `static` in Java.

DO make the mandatory adjustments:
- Map Java types to Kotlin types: `int` → `Int`, `long` → `Long`, `boolean` → `Boolean`, `String` → `String`, `Object` → `Any?` (or `Any` if non-null is clear), arrays → `IntArray`/`Array<T>`/etc. as appropriate.
- Remove trailing `;` statement terminators.
- `final` fields → `val`; non-`final` → `var`.
- Java `static` members → `companion object` (or top-level if the Java already kept the class purely as a namespace — but prefer `companion object` to stay structural).
- Java constructors → primary or secondary constructors; preserve which fields are initialised in which constructor.
- Java getters/setters: if the Java class has a private field plus a public `getX()`/`setX()` pair following bean conventions, you may render as a Kotlin `var`/`val` property. If the getter has logic, keep it as an explicit `get()` accessor.
- Method overrides: add the Kotlin `override` keyword. Drop `@Override` annotations.
- Nullability: when a Java parameter or field is annotated `@Nullable`, render the Kotlin type as `T?` and DROP the `@Nullable` annotation — Kotlin's `?` already expresses nullability, and JSR-305 `@Nullable` is not valid in Kotlin type-use position. Same for `@NotNull`/`@NonNull` on non-null types (just drop them). Otherwise treat parameters and returns of reference types as non-null unless Java code can clearly produce null (then mark `T?`).
- Kotlin reserved-word identifiers: if a Java identifier (parameter, field, method name) clashes with a Kotlin keyword (`object`, `fun`, `val`, `var`, `class`, `is`, `in`, `out`, etc.), wrap it in backticks in the Kotlin output — for example a Java parameter named `object` becomes the backtick-quoted Kotlin name (literally a backtick character, then `object`, then a backtick character). Do NOT rename it — the faithfulness rule on parameter names trumps the keyword conflict.
- Method calls vs. property access: a Java method call `obj.foo()` translates to a Kotlin method call `obj.foo()` — keep the parentheses. Do NOT silently drop the parentheses to turn it into a property access (`obj.foo`). This is especially important for `size()`, `length()`, `get()`, `isEmpty()`, and other zero-arg methods, which look like Kotlin properties but are NOT properties in this codebase. Only render as a property if the Java declaration is a real field (e.g. `public int x;` → `val x: Int`).
- Checked exceptions: Kotlin has no checked exceptions. Drop `throws` clauses. Do NOT add `@Throws` annotations unless the Java method was specifically annotated.
- Generics: `? extends T` → `out T`; `? super T` → `in T`; raw `T[]` → `Array<T>`; primitive arrays → `IntArray` etc.
- Nested classes: Java static nested → Kotlin nested class (no `inner` keyword). Java non-static inner → Kotlin `inner class`.
- `interface` with `default` methods → Kotlin `interface` with body; preserve.
- `enum` constants with bodies → Kotlin enum entries with overridden methods.

# Output format

Return ONLY a single fenced code block, starting with ```kotlin and ending with ```. No prose before or after.
"""


CATEGORY_ADDENDA: dict[str, str] = {
    "interface": (
        "This file declares an interface. In Kotlin, abstract methods need no `abstract` keyword. "
        "Properties declared in interfaces use `val`/`var` without an initialiser. Default methods carry over with the same body."
    ),
    "enum": (
        "This file declares an enum. Java enum constants with bodies become Kotlin enum entries that override methods inline. "
        "If the enum declares fields, render them as a primary constructor on the enum class. "
        "Preserve the order of enum constants exactly."
    ),
    "annotation": (
        "This file declares an annotation type. Translate to Kotlin `annotation class`. "
        "Preserve `@Target`, `@Retention`, and `@Repeatable` meta-annotations. "
        "Methods on the Java annotation become primary constructor properties on the Kotlin annotation class."
    ),
    "record": (
        "This file declares a Java `record`. A Java record can be translated to a Kotlin `data class` "
        "since records are by construction simple value containers — this is the one exception to the no-`data-class` rule. "
        "Map the record header parameters to the data class primary constructor."
    ),
    "test": (
        "This file is a JUnit test. Keep all annotations (`@Test`, `@BeforeEach`, `@ParameterizedTest`, etc.) verbatim — "
        "they translate to identical Kotlin annotations. Keep test method names exactly as-is, even if they contain underscores. "
        "Do NOT restructure assertions, do NOT switch from JUnit to Kotest, do NOT collapse multi-line setup."
    ),
}


@dataclass
class TranslatePromptInputs:
    java_source: str
    java_filename: str
    package: str
    category: str
    dependency_signatures: str  # may be empty


def build_translate_messages(inputs: TranslatePromptInputs) -> list[dict[str, str]]:
    addendum = CATEGORY_ADDENDA.get(inputs.category, "")
    addendum_block = f"\n# Category-specific note\n\n{addendum}\n" if addendum else ""

    deps_block = ""
    if inputs.dependency_signatures.strip():
        deps_block = (
            "\n# Context — Kotlin signatures of types this file depends on\n\n"
            "These types have already been converted. Match their names and types when referring to them.\n\n"
            f"{inputs.dependency_signatures}\n"
        )

    user_msg = (
        f"Translate this Java file to Kotlin, following all the rules.\n\n"
        f"Filename: `{inputs.java_filename}`\n"
        f"Package: `{inputs.package}`\n"
        f"Category: `{inputs.category}`\n"
        f"{addendum_block}"
        f"{deps_block}"
        f"\n# Java source\n\n```java\n{inputs.java_source}\n```\n"
    )

    return [
        {"role": "system", "content": SYSTEM_INSTRUCTION},
        {"role": "user", "content": user_msg},
    ]


RETRY_INSTRUCTION_TAIL = """\
The previous translation did not compile. Below is the original Java, the Kotlin you produced last time, and the kotlinc error output.

Produce a corrected Kotlin file. Apply the same faithfulness rules — do NOT take this as license to refactor. Fix only what the compiler error requires.

Same style rules as before: no deliberation, no thinking out loud, no chain-of-thought. Edit the previous attempt to fix the specific compile errors and emit the result. If the compiler error names a symbol you don't recognise (e.g. an `unresolved reference 'Foo'`), that usually means a project type hasn't been translated yet — keep referencing it as if it exists; do not invent stubs or change its name.

Return ONLY a single fenced ```kotlin code block. No prose.
"""


@dataclass
class RetryPromptInputs:
    java_source: str
    java_filename: str
    package: str
    category: str
    previous_kotlin: str
    compiler_error: str
    dependency_signatures: str


def build_retry_messages(inputs: RetryPromptInputs) -> list[dict[str, str]]:
    addendum = CATEGORY_ADDENDA.get(inputs.category, "")
    addendum_block = f"\n# Category-specific note\n\n{addendum}\n" if addendum else ""

    deps_block = ""
    if inputs.dependency_signatures.strip():
        deps_block = (
            "\n# Context — Kotlin signatures of types this file depends on\n\n"
            f"{inputs.dependency_signatures}\n"
        )

    user_msg = (
        f"{RETRY_INSTRUCTION_TAIL}\n"
        f"Filename: `{inputs.java_filename}`\n"
        f"Package: `{inputs.package}`\n"
        f"Category: `{inputs.category}`\n"
        f"{addendum_block}"
        f"{deps_block}"
        f"\n# Original Java\n\n```java\n{inputs.java_source}\n```\n"
        f"\n# Previous Kotlin attempt\n\n```kotlin\n{inputs.previous_kotlin}\n```\n"
        f"\n# kotlinc error output\n\n```\n{inputs.compiler_error}\n```\n"
    )

    return [
        {"role": "system", "content": SYSTEM_INSTRUCTION},
        {"role": "user", "content": user_msg},
    ]


# ---------------------------------------------------------------------------
# Kotlin signature extraction for dependency context
# ---------------------------------------------------------------------------


import re as _re

_RE_KT_PACKAGE = _re.compile(r"^\s*package\s+([\w.]+)\s*$", _re.MULTILINE)
_RE_KT_DECL = _re.compile(
    r"^\s*(?:public\s+|private\s+|protected\s+|internal\s+|open\s+|abstract\s+|final\s+|sealed\s+|inline\s+|external\s+|infix\s+|suspend\s+|operator\s+|tailrec\s+|override\s+|companion\s+|data\s+|enum\s+|annotation\s+|inner\s+|object\s+)*"
    r"(class|interface|object|fun|val|var)\b[^\n{=]*",
    _re.MULTILINE,
)


def extract_kotlin_signatures(kotlin_source: str, max_chars: int) -> str:
    """Pull declarations (without bodies) out of a Kotlin file for use as dep context.

    Best-effort string scan — keeps top-level package + each declaration line up to
    the body brace / assignment.
    """
    out: list[str] = []
    pkg = _RE_KT_PACKAGE.search(kotlin_source)
    if pkg:
        out.append(f"package {pkg.group(1)}")

    for m in _RE_KT_DECL.finditer(kotlin_source):
        line = m.group(0).strip()
        # Strip a trailing brace remnant if any.
        line = line.rstrip()
        out.append(line)

    blob = "\n".join(out)
    if len(blob) > max_chars:
        blob = blob[:max_chars] + "\n// ...truncated..."
    return blob
