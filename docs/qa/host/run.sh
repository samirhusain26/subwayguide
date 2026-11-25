#!/usr/bin/env bash
# Render the actual Android views with Robolectric native graphics.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../../.." && pwd)"
out="${1:-${SUBWAY_QA_OUTPUT:-${TMPDIR:-/tmp}/subwayguide-ui}}"
scratch="${SUBWAY_QA_CACHE:-${XDG_CACHE_HOME:-$HOME/.cache}/subwayguide-host-qa}"
jdk="${JAVA_HOME:?Set JAVA_HOME to a JDK with javac}/bin"
sdk="${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK}"
maven="${MAVEN:-mvn}"
android_jar="$sdk/platforms/android-35/android.jar"
if [[ ! -f "$android_jar" ]]; then
  android_jar="$sdk/platforms/android-36/android.jar"
fi
mkdir -p "$scratch"/{classes,maven-cache,libs,temp} "$out"

"$maven" -q -B -f "$here/pom.xml" -Dmaven.repo.local="$scratch/maven-cache" \
  org.apache.maven.plugins:maven-dependency-plugin:3.9.0:build-classpath \
  -Dmdep.outputFile="$scratch/classpath"

classpath="$(cat "$scratch/classpath")"
# Maven returns AndroidX test libraries as AARs. The JVM uses their classes.jar.
IFS=: read -ra libraries <<< "$classpath"
for library in "${libraries[@]}"; do
  if [[ "$library" == *.aar ]]; then
    jar="$scratch/libs/$(basename "$library" .aar).jar"
    unzip -p "$library" classes.jar > "$jar"
    classpath="$classpath:$jar"
  fi
done

"$jdk/javac" --release 17 -classpath "$android_jar:$classpath" -d "$scratch/classes" \
  "$root"/android/src/main/java/nyc/subway/board/*.java "$here"/*.java
suites=()
for source in "$here"/*Test.java; do
  suites+=("nyc.subway.board.$(basename "$source" .java)")
done
"$jdk/java" -Xmx1550m -Djava.awt.headless=true \
  -Djava.io.tmpdir="$scratch/temp" -Dmaven.repo.local="$scratch/maven-cache" \
  -Drobolectric.dependency.repo.url=https://repo.maven.apache.org/maven2 \
  -DassetDir="$root/android/app/src/main/assets" -DcatalogFile="$root/server/stations.json" -DoutDir="$out" \
  -classpath "$scratch/classes:$classpath:$android_jar" \
  org.junit.runner.JUnitCore "${suites[@]}"
