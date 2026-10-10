#!/usr/bin/env bash
set -euo pipefail

# This host test compiles the production JNI source; run it in Linux CI.
: "${JAVA_HOME:?JAVA_HOME must point to a JDK with JNI headers}"
repo=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
test_dir=$(mktemp -d "${TMPDIR:-${RUNNER_TEMP:?Set TMPDIR or RUNNER_TEMP}}/aether-pidfd-test.XXXXXX")
trap 'rm -rf -- "$test_dir"' EXIT
for variant in supported missing-definitions; do
    extra_flags=()
    if [[ "$variant" == missing-definitions ]]; then
        extra_flags=(-DTEST_WITHOUT_PIDFD)
    fi
    "${CC:-cc}" -std=c11 -Wall -Wextra -Werror -pthread \
        -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" "${extra_flags[@]}" \
        "$repo/scripts/session-validate/pidfd-support-test.c" -o "$test_dir/$variant"
    "$test_dir/$variant"
done
