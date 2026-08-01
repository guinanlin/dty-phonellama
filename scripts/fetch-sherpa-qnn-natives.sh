#!/usr/bin/env bash
# Extract Qualcomm QNN + QNN-enabled sherpa native libs from the official
# SenseVoice QNN APK into app/src/main/jniLibs/arm64-v8a/.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT_DIR="$ROOT/app/src/main/jniLibs/arm64-v8a"
CACHE_DIR="${SHERPA_QNN_CACHE_DIR:-$ROOT/.cache/sherpa-qnn}"
APK_VERSION="${SHERPA_QNN_APK_VERSION:-1.12.17}"
# Natives only; model length does not matter. Prefer smaller 5s APK.
APK_NAME="sherpa-onnx-${APK_VERSION}-qnn-arm64-v8a-simulated_streaming_asr-zh_en_ko_ja_yue-5-seconds-sense_voice_2024_07_17_int8.apk"
APK_PATH_REL="qnn-vad-asr-simulated-streaming/${APK_VERSION}/${APK_NAME}"

HF_URL="https://huggingface.co/csukuangfj/sherpa-onnx-apk/resolve/main/${APK_PATH_REL}"
MIRROR_URL="https://hf-mirror.com/csukuangfj/sherpa-onnx-apk/resolve/main/${APK_PATH_REL}"

mkdir -p "$OUT_DIR" "$CACHE_DIR"
APK_FILE="$CACHE_DIR/$APK_NAME"

need_download=1
if [[ -f "$APK_FILE" ]]; then
  size="$(wc -c < "$APK_FILE" | tr -d ' ')"
  if [[ "$size" -gt 100000000 ]]; then
    need_download=0
    echo "Using cached APK: $APK_FILE ($size bytes)"
  fi
fi

if [[ "$need_download" -eq 1 ]]; then
  echo "Downloading SenseVoice QNN APK for native libs..."
  TMP="${APK_FILE}.tmp"
  ok=0
  for url in "$MIRROR_URL" "$HF_URL"; do
    echo "TRY $url"
    if curl -L --fail --retry 5 --retry-delay 2 -o "$TMP" "$url"; then
      size="$(wc -c < "$TMP" | tr -d ' ')"
      if [[ "$size" -gt 100000000 ]]; then
        mv "$TMP" "$APK_FILE"
        ok=1
        echo "Saved $APK_FILE ($size bytes)"
        break
      fi
    fi
    rm -f "$TMP"
  done
  if [[ "$ok" -ne 1 ]]; then
    echo "Failed to download QNN APK" >&2
    exit 1
  fi
fi

WORK="$CACHE_DIR/extract-$$"
mkdir -p "$WORK"
trap 'rm -rf "$WORK"' EXIT

echo "Extracting arm64-v8a native libraries..."
unzip -qo "$APK_FILE" 'lib/arm64-v8a/*.so' -d "$WORK"

SRC="$WORK/lib/arm64-v8a"
if [[ ! -d "$SRC" ]]; then
  echo "APK missing lib/arm64-v8a" >&2
  exit 1
fi

# Keep QNN runtime + sherpa/onnxruntime natives. Drop unrelated app libs if any.
copied=0
for f in "$SRC"/*.so; do
  base="$(basename "$f")"
  case "$base" in
    libQnn*.so|libsherpa-onnx*.so|libonnxruntime.so)
      cp -f "$f" "$OUT_DIR/$base"
      copied=$((copied + 1))
      echo "  + $base"
      ;;
  esac
done

if [[ "$copied" -lt 3 ]]; then
  echo "Expected QNN/sherpa natives, only copied $copied" >&2
  ls -lh "$SRC" >&2 || true
  exit 1
fi

# Sanity: HTP backend + system lib must exist.
test -f "$OUT_DIR/libQnnHtp.so" || { echo "missing libQnnHtp.so" >&2; exit 1; }
test -f "$OUT_DIR/libQnnSystem.so" || { echo "missing libQnnSystem.so" >&2; exit 1; }
test -f "$OUT_DIR/libsherpa-onnx-jni.so" || { echo "missing libsherpa-onnx-jni.so" >&2; exit 1; }

echo "QNN natives ready in $OUT_DIR ($copied libs)"
ls -lh "$OUT_DIR" | head -40

# Drop jni from the stock AAR so app/src/main/jniLibs wins without packaging pickFirst
# (AGP packaging DSL conflicts with the oss-licenses plugin in this project).
AAR="$(ls -1 "$ROOT"/app/libs/sherpa-onnx-*.aar 2>/dev/null | head -1 || true)"
if [[ -n "$AAR" && -f "$AAR" ]]; then
  echo "Stripping jni/ from $AAR to avoid duplicate .so with jniLibs..."
  STRIP_WORK="$CACHE_DIR/strip-aar-$$"
  mkdir -p "$STRIP_WORK"
  unzip -qo "$AAR" -d "$STRIP_WORK"
  if [[ -d "$STRIP_WORK/jni" ]]; then
    rm -rf "$STRIP_WORK/jni"
    (
      cd "$STRIP_WORK"
      zip -qr "$AAR.tmp" .
    )
    mv "$AAR.tmp" "$AAR"
    echo "AAR jni stripped: $AAR ($(wc -c < "$AAR" | tr -d ' ') bytes)"
  else
    echo "AAR has no jni/ directory; leaving as-is"
  fi
  rm -rf "$STRIP_WORK"
fi
