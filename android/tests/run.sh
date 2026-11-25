#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
classes="$(mktemp -d "${TMPDIR:-/tmp}/subway-policy.XXXXXX")"
trap 'rm -rf "$classes"' EXIT
javac -d "$classes" android/src/main/java/nyc/subway/board/{Decision,ChimePolicy,MultiRoutePolicy}.java android/tests/*.java
for suite in android/tests/*Test.java; do
  name="$(basename "$suite" .java)"
  java -ea -cp "$classes" "nyc.subway.board.$name"
done
