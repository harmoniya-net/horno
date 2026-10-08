#!/bin/sh
# Ask the JDK on PATH (or $JAVA_HOME) the questions in JdkInternals.java, of a
# built horno jar:
#
#   jdk-probe/run.sh build/libs/horno-0.1.5.jar
#
# The probe needs three things on the class path from the start — the jar, its
# own classes with a URL handler provider registered, and a directory the
# "produced library" has to get ahead of — which is all this sets up.
set -eu

jar=$(realpath "$1")
here=$(cd "$(dirname "$0")" && pwd)
bin=${JAVA_HOME:+$JAVA_HOME/bin/}
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

mkdir -p "$work/classes/META-INF/services" "$work/old/horno-probe" "$work/libraries/new/horno-probe"
"${bin}javac" -nowarn -cp "$jar" -d "$work/classes" "$here/JdkInternals.java"
echo 'JdkInternals$Provider' > "$work/classes/META-INF/services/java.net.spi.URLStreamHandlerProvider"
echo old > "$work/old/horno-probe/which"
echo new > "$work/libraries/new/horno-probe/which"

"${bin}java" -cp "$jar:$work/classes:$work/old" JdkInternals "$work/libraries" new
