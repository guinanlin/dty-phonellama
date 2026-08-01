#!/usr/bin/env bash
# Download the vendored sherpa-onnx Android AAR into app/libs/.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VERSION="${SHERPA_ONNX_VERSION:-1.13.4}"
OUT_DIR="$ROOT/app/libs"
OUT_FILE="$OUT_DIR/sherpa-onnx-${VERSION}.aar"
URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v${VERSION}/sherpa-onnx-${VERSION}.aar"

mkdir -p "$OUT_DIR"
if [[ -f "$OUT_FILE" ]]; then
  if unzip -l "$OUT_FILE" 2>/dev/null | grep -q 'classes.jar'; then
    size="$(wc -c < "$OUT_FILE" | tr -d ' ')"
    echo "Already present: $OUT_FILE ($size bytes)"
    exit 0
  fi
fi

echo "Downloading $URL"
TMP="${OUT_FILE}.tmp"
if command -v curl >/dev/null 2>&1; then
  curl -L --fail --retry 5 --retry-delay 2 -o "$TMP" "$URL"
else
  wget -O "$TMP" "$URL"
fi

size="$(wc -c < "$TMP" | tr -d ' ')"
if [[ "$size" -lt 40000000 ]]; then
  echo "Downloaded AAR looks too small ($size bytes)" >&2
  rm -f "$TMP"
  exit 1
fi
mv "$TMP" "$OUT_FILE"
echo "Saved $OUT_FILE ($size bytes)"
