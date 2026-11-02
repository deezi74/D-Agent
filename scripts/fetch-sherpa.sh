#!/usr/bin/env bash
# Downloads the sherpa-onnx Android library (Apache-2.0) used for on-phone voices and listening.
set -euo pipefail
OUT="${1:-app/libs}"
VER="${SHERPA_VERSION:-1.13.8}"
mkdir -p "$OUT"
URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v$VER/sherpa-onnx-$VER.aar"
for i in 1 2 3 4; do
  curl -fsSL --retry 3 -o "$OUT/sherpa-onnx.aar" "$URL" && break
  sleep $((i * 4))
done
unzip -tq "$OUT/sherpa-onnx.aar"
echo "sherpa-onnx $VER ready ($(du -h "$OUT/sherpa-onnx.aar" | cut -f1))"
