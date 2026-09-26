#!/usr/bin/env bash
# Wipe the llamatik-native CMake/Gradle caches.
#
# Why this exists (mirrors external/imagedecoder-houri/AGENTS.md):
#   AGP keeps .cxx/<Variant>/<hash>/<abi>/CMakeCache.txt between runs. That cache pins the
#   results of find_program() and our own set(... CACHE "" FORCE) calls, so a configure that
#   could not see glslc keeps reporting NOTFOUND even after the tool appears -- and a probed
#   WSL proxy stays pinned too. The symptom is "I fixed CMakeLists.txt and nothing changed".
#   Same for .gradle/configuration-cache holding a stale configuration on top of that.
#
# Run it after editing CMakeLists.txt, switching NDK, or changing any -Pmtl.* property.
#
#   ./wipe-cache.sh              # wipe only
#   ./wipe-cache.sh --build      # wipe, then run the build that was passed as args
#   ./wipe-cache.sh --build :llamatik-native:assembleDebug -Pmtl.gpuOffload=false
#
# Safe to run at any time; everything removed here is generated and re-derivable.

set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "$here/.." && pwd)"

build=0
if [[ "${1:-}" == "--build" ]]; then
  build=1
  shift
fi

# Stop the daemon first: a warm daemon can hold the CMake cache in memory and write it back
# after we delete the files, which resurrects the stale state.
if [[ -x "$repo/gradlew" ]]; then
  echo "==> stopping Gradle daemons"
  "$repo/gradlew" --stop >/dev/null 2>&1 || true
fi

# .cxx alone is not enough. The "DefaultAndroidLibrarySourceSet_Decorated cannot be cast to
# AndroidLibrarySourceSet" failure is a stale AGP jar / regenerated decorator in the build-script
# caches, so those have to go too. --all includes them; the default stays quick.
targets=("$here/.cxx" "$repo/.gradle/configuration-cache")
if [[ "${1:-}" == "--all" ]]; then
  shift
  targets+=("$repo/.gradle" "$repo/buildSrc/.gradle" "$repo/buildSrc/build")
fi

for target in "${targets[@]}"; do
  if [[ -e "$target" ]]; then
    echo "==> removing ${target#"$repo/"}"
    rm -rf "$target"
  else
    echo "==> absent  ${target#"$repo/"}"
  fi
done

if [[ $build -eq 1 ]]; then
  if [[ $# -eq 0 ]]; then
    set -- :llamatik-native:assembleDebug
  fi
  # --no-configuration-cache / --rerun-tasks mirror imagedecoder's documented recovery.
  echo "==> building: $*"
  exec "$repo/gradlew" "$@" --no-configuration-cache --rerun-tasks
fi

echo
echo "Done. Now re-run your build (the next configure will re-probe glslc)."
