#!/usr/bin/env bash
# Bundles the MLC-LLM Android runtime (mlc4j) + compiled model libraries into the app so the
# "Local (On-device LLM)" translation provider actually ships in the mtl variant.
#
# The mlc4j runtime is NOT on Maven Central — it must be built from the MLC-LLM source tree
# (`mlc_llm package`). This script:
#   1. Skips when the runtime is already vendored (yakuyomi-engine/src/main/jniLibs).
#   2. Tries to download prebuilt artifacts from the MLCL4J_RELEASE_URL (a GitHub release asset)
#      when provided — fastest path for CI.
#   3. Falls back to building from source (pinned MLC-LLM tag) and copies the outputs into
#      yakuyomi-engine. Fail-soft: if the build fails, the app still compiles (the local-LLM
#      provider is driven through reflection and simply reports "runtime not bundled"), but the
#      build log will say so loudly.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENGINE_DIR="$REPO_ROOT/yakuyomi-engine"
JNI_DIR="$ENGINE_DIR/src/main/jniLibs"
MLC_TAG="${MLC_TAG:-v0.20.0}"          # pinned MLC-LLM release
MLC4J_RELEASE_URL="${MLC4J_RELEASE_URL:-}"
ABIS=("arm64-v8a" "armeabi-v7a")

# Already bundled? (e.g. vendored after a previous build)
if [ -f "$JNI_DIR/arm64-v8a/libtvm4j_runtime_packed.so" ]; then
  echo "mlc4j runtime already vendored; skipping bundling."
  exit 0
fi

# 1) Prebuilt release asset (fast path)
if [ -n "$MLC4J_RELEASE_URL" ]; then
  echo "Downloading prebuilt mlc4j from $MLC4J_RELEASE_URL"
  TMP_DIR="$(mktemp -d)"
  if curl --fail --silent --show-error -L "$MLC4J_RELEASE_URL" -o "$TMP_DIR/mlc4j.tar.gz"; then
    tar -xzf "$TMP_DIR/mlc4j.tar.gz" -C "$TMP_DIR"
    # Expect libtvm4j_runtime_packed.so per ABI + tvm4j_core.jar inside
    for abi in "${ABIS[@]}"; do
      if [ -f "$TMP_DIR/$abi/libtvm4j_runtime_packed.so" ]; then
        mkdir -p "$JNI_DIR/$abi"
        cp "$TMP_DIR/$abi/libtvm4j_runtime_packed.so" "$JNI_DIR/$abi/"
      fi
    done
    if [ -f "$TMP_DIR/tvm4j_core.jar" ]; then
      mkdir -p "$ENGINE_DIR/libs"
      cp "$TMP_DIR/tvm4j_core.jar" "$ENGINE_DIR/libs/"
    fi
    rm -rf "$TMP_DIR"
    echo "Prebuilt mlc4j bundled."
    exit 0
  fi
  rm -rf "$TMP_DIR"
  echo "Prebuilt download failed; falling back to building from source."
fi

# 2) Build from source
echo "Building mlc4j from MLC-LLM $MLC_TAG (this can take a while)..."
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
git clone --depth 1 --branch "$MLC_TAG" https://github.com/mlc-ai/mlc-llm.git "$WORK/mlc-llm"
cd "$WORK/mlc-llm"
git submodule update --init --recursive

# The mlc_llm python package ships as prebuilt wheels on MLC's own index (not PyPI).
# Use a venv: the host Python is PEP 668 externally-managed (Homebrew/Ubuntu).
python3 -m venv "$WORK/venv"
"$WORK/venv/bin/pip" install --quiet --pre -U -f https://mlc.ai/wheels mlc-llm-nightly-cpu mlc-ai-nightly-cpu

# Package config: compile the runtime plus the catalog's MLC model libraries for Android.
cat > android/MLCChat/mlc-package-config.json <<'EOF'
{
  "device": "android",
  "model_list": [
    { "model": "HF://mlc-ai/Qwen2.5-1.5B-Instruct-q4f16_1-MLC", "model_id": "Qwen2.5-1.5B-Instruct-q4f16_1-MLC", "estimated_vram_bytes": 1000000000 },
    { "model": "HF://mlc-ai/Qwen2.5-3B-Instruct-q4f16_1-MLC", "model_id": "Qwen2.5-3B-Instruct-q4f16_1-MLC", "estimated_vram_bytes": 2000000000 },
    { "model": "HF://mlc-ai/gemma-2-2b-it-q4f16_1-MLC", "model_id": "gemma-2-2b-it-q4f16_1-MLC", "estimated_vram_bytes": 1600000000 },
    { "model": "HF://mlc-ai/Llama-3.2-1B-Instruct-q4f16_1-MLC", "model_id": "Llama-3.2-1B-Instruct-q4f16_1-MLC", "estimated_vram_bytes": 700000000 },
    { "model": "HF://mlc-ai/gemma-3-4b-it-q4f16_1-MLC", "model_id": "gemma-3-4b-it-q4f16_1-MLC", "estimated_vram_bytes": 2800000000 }
  ]
}
EOF

export MLC_LLM_SOURCE_DIR="$WORK/mlc-llm"
export ANDROID_NDK="${ANDROID_NDK:-$ANDROID_HOME/ndk/27.0.12077973}"
cd android/MLCChat
# The wheel exposes the CLI as a module (`python -m mlc_llm`), not a console script.
"$WORK/venv/bin/python" -m mlc_llm package || {
  echo "::warning::mlc_llm package failed — the mtl build will ship without the on-device LLM runtime. Check the MLC build log above."
  exit 0
}

# Copy outputs into the engine module.
for abi in "${ABIS[@]}"; do
  if [ -f "$WORK/mlc-llm/android/MLCChat/dist/lib/mlc4j/output/$abi/libtvm4j_runtime_packed.so" ]; then
    mkdir -p "$JNI_DIR/$abi"
    cp "$WORK/mlc-llm/android/MLCChat/dist/lib/mlc4j/output/$abi/libtvm4j_runtime_packed.so" "$JNI_DIR/$abi/"
  fi
done
if [ -f "$WORK/mlc-llm/android/MLCChat/dist/lib/mlc4j/output/tvm4j_core.jar" ]; then
  mkdir -p "$ENGINE_DIR/libs"
  cp "$WORK/mlc-llm/android/MLCChat/dist/lib/mlc4j/output/tvm4j_core.jar" "$ENGINE_DIR/libs/"
fi
# Compiled model libraries (bundled so system://<modelLib> resolves at runtime)
MODEL_LIB_DIR="$ENGINE_DIR/src/main/assets/mlc-model-libs"
mkdir -p "$MODEL_LIB_DIR"
find "$WORK/mlc-llm/android/MLCChat/dist" -name "lib*.so" -path "*output*" -exec cp {} "$MODEL_LIB_DIR/" \;
echo "mlc4j bundled from source."