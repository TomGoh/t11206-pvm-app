#!/bin/sh
# Host test of the app's TerminalBuffer against the console output the x-kernel guest really
# sends, with stubs for the few android.* classes it uses. Needs a JDK (17 or later) on PATH:
#   host-tests/run.sh
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
javac -d "$OUT" $(find "$HERE/stubs" "$HERE/src" -name '*.java') \
    "$HERE/../src/com/kylinos/xkernelvm/control/TerminalBuffer.java"
java -cp "$OUT" com.kylinos.xkernelvm.control.TerminalBufferTest
