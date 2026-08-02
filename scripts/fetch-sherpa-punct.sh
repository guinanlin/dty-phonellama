#!/usr/bin/env bash
# Download sherpa-onnx OfflinePunctuation (ct-transformer zh-en int8) for optional
# offline / adb push into the device app files dir.
#
# Device path used by the app (auto-download also lands here):
#   /sdcard/Android/data/com.phonellama.app/files/sherpa-punct/
#
# Example adb push after this script:
#   adb push "$OUT_DIR" /sdcard/Android/data/com.phonellama.app/files/sherpa-punct/
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT_DIR="${1:-$ROOT/third_party/sherpa-punct}"
ARCHIVE_NAME="sherpa-onnx-punct-ct-transformer-zh-en-vocab272727-2024-04-12-int8.tar.bz2"
URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/punctuation-models/${ARCHIVE_NAME}"
MODEL_REL="sherpa-onnx-punct-ct-transformer-zh-en-vocab272727-2024-04-12-int8/model.int8.onnx"

mkdir -p "$OUT_DIR"
MODEL_FILE="$OUT_DIR/$MODEL_REL"
if [[ -f "$MODEL_FILE" ]]; then
  size="$(wc -c < "$MODEL_FILE" | tr -d ' ')"
  echo "Already present: $MODEL_FILE ($size bytes)"
  exit 0
fi

TMP="$OUT_DIR/$ARCHIVE_NAME"
echo "Downloading $URL"
if command -v curl >/dev/null 2>&1; then
  curl -L --fail --retry 5 --retry-delay 2 -o "$TMP" "$URL"
else
  wget -O "$TMP" "$URL"
fi

echo "Extracting into $OUT_DIR"
tar -xjf "$TMP" -C "$OUT_DIR"
rm -f "$TMP"

if [[ ! -f "$MODEL_FILE" ]]; then
  echo "Expected model missing after extract: $MODEL_FILE" >&2
  exit 1
fi
echo "Saved $MODEL_FILE ($(wc -c < "$MODEL_FILE" | tr -d ' ') bytes)"
