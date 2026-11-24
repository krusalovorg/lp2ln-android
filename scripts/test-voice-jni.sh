#!/usr/bin/env bash
set -euo pipefail
lib=$(realpath "${1:?path to host liblp2ln_android.so}")
root=$(cd "$(dirname "$0")/.." && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
javac -d "$work" "$root/rust-preview/tests/VoiceJniSmoke.java"
main=lp2ln_android.krusalov.org.network.VoiceJniSmoke
timeout 40 java -cp "$work" "$main" "$lib" "$work" listener >"$work/listener.log" 2>&1 &
listener=$!
if ! timeout 40 java -cp "$work" "$main" "$lib" "$work" caller >"$work/caller.log" 2>&1; then
  cat "$work/caller.log" "$work/listener.log"; wait "$listener" || true; exit 1
fi
status=0
wait "$listener" || status=$?
cat "$work/caller.log" "$work/listener.log"
exit "$status"
