#!/usr/bin/env bash
# Resolve the JVM classpath for the source project and emit one absolute JAR
# path per line on stdout. Used by the pipeline's verify phase so kotlinc can
# see third-party annotations and APIs (e.g. @FormatMethod, @Nullable, vertx
# Buffer) referenced by the translated Kotlin.
#
# Defaults are tuned for the tuweni `bytes` module — override via env vars to
# point at a different project / module.

set -euo pipefail

PROJECT_DIR="${PROJECT_DIR:-${HOME}/tuweni-gemma4-pipe}"
GRADLE_MODULE="${GRADLE_MODULE:-:bytes}"
INCLUDE_TESTS="${INCLUDE_TESTS:-1}"

INIT_SCRIPT="$(mktemp -t print-classpath.XXXXXX.gradle)"
trap 'rm -f "$INIT_SCRIPT"' EXIT

cat > "$INIT_SCRIPT" <<'EOF'
allprojects {
    afterEvaluate { p ->
        if (p.configurations.findByName('compileClasspath')) {
            p.tasks.register('printCompileClasspath') {
                doLast {
                    p.configurations.compileClasspath.resolvedConfiguration.files.each {
                        println "COMPILE\t" + it
                    }
                }
            }
        }
        if (p.configurations.findByName('testCompileClasspath')) {
            p.tasks.register('printTestClasspath') {
                doLast {
                    p.configurations.testCompileClasspath.resolvedConfiguration.files.each {
                        println "TEST\t" + it
                    }
                }
            }
        }
    }
}
EOF

cd "$PROJECT_DIR"

TASKS=("${GRADLE_MODULE}:printCompileClasspath")
if [ "$INCLUDE_TESTS" = "1" ]; then
    TASKS+=("${GRADLE_MODULE}:printTestClasspath")
fi

./gradlew --init-script "$INIT_SCRIPT" -q "${TASKS[@]}" \
    | awk -F'\t' 'NF==2 {print $2}' \
    | sort -u
