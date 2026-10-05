#!/usr/bin/env bash
# Offline type-check of pure-JVM Kotlin sources with the compiler bundled in a Gradle distribution.
# Usage: scripts/kc.sh <out-dir> <extra-classpath or ''> <sources...>
# Not a substitute for the Gradle build; used in the cloud session where Maven is unreachable.
set -euo pipefail
GL=${GRADLE_LIB:-/opt/gradle-8.14.3/lib}
OUT=$1; EXTRA=$2; shift 2
TOOLCP=$(ls "$GL"/kotlin-*.jar "$GL"/kotlinx-coroutines-core-jvm-*.jar "$GL"/trove4j*.jar "$GL"/annotations-*.jar 2>/dev/null | tr '\n' ':')
LIBCP="$GL/kotlin-stdlib-2.0.21.jar:$GL/kotlinx-coroutines-core-jvm-1.6.4.jar:$GL/kotlinx-serialization-core-jvm-1.6.2.jar:$GL/kotlinx-serialization-json-jvm-1.6.2.jar:$GL/junit-4.13.2.jar:$GL/hamcrest-core-1.3.jar"
mkdir -p "$OUT"
java -cp "$TOOLCP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 \
  -cp "$LIBCP${EXTRA:+:$EXTRA}" -d "$OUT" "$@" 2>&1 | grep -v JAVA_TOOL_OPTIONS || true
