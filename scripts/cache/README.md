# Cache wipe scripts (PowerShell)

These are the **PowerShell** equivalents of the `rm -rf .cxx/.gradle/~/.gradle/caches` you tried on WSL/bash.
They handle the `external/imagedecoder-houri` libiconv stale-cache issue that survives a plain `.cxx` wipe (ExternalProject patch stamp + Gradle configuration-cache + `C:/` vs `/mnt/c/` host mis-detection).

All scripts are **idempotent**, use `-ErrorAction SilentlyContinue`, and handle `MAX_PATH 260` via robocopy fallback. Run from repo root in **PowerShell** (not WSL bash).

## Quick use

```powershell
# Preview what would be deleted (dry run)
powershell -ExecutionPolicy Bypass -File scripts\cache\wipe-imagedecoder-cache.ps1 -DryRun
powershell -ExecutionPolicy Bypass -File scripts\cache\wipe-all-caches.ps1 -DryRun

# Targeted: only libiconv (fixes the FAILED: ep_libiconv-build you just hit)
powershell -ExecutionPolicy Bypass -File scripts\cache\wipe-imagedecoder-cache.ps1

# Gradle only (changed libs.versions.toml / AGP)
powershell -ExecutionPolicy Bypass -File scripts\cache\wipe-gradle-caches.ps1

# Nuclear: everything (recommended after the f480abb/9a7fe0d fix)
powershell -ExecutionPolicy Bypass -File scripts\cache\wipe-all-caches.ps1
# will prompt YES; use -NoConfirm to skip prompt in CI
```

## What each does

| Script | Stops daemon | Wipes |
|--------|--------------|-------|
| `wipe-imagedecoder-cache.ps1` | `gradlew.bat --stop` | `external/imagedecoder-houri/library/.cxx`, `build`, `.gradle/configuration-cache`, `external/imagedecoder-houri/.gradle`, `~/.gradle/caches/build-cache-1` + `transforms-*` + versioned `8.*`/`9.*` |
| `wipe-gradle-caches.ps1` | yes | project `.gradle/.kotlin/.gradle-home/build` + all `external/*/.gradle` + home `daemon`, `build-cache-*`, `transforms-*`, `modules-*`, `jars-*`, `journal-*` |
| `wipe-all-caches.ps1` | yes | **both of the above** + every stray `.cxx` under `external/` and `app/.cxx`, every `.gradle` under repo, plus home caches with robocopy fallback for long paths |

## After wiping

```powershell
# Ensure you are on the fixed commits
git submodule update --init --recursive external\imagedecoder-houri
git -C external\imagedecoder-houri log --oneline -1  # should be 9a7fe0d
git log --oneline -1                                  # should be 5f26365

# Sync in Android Studio or:
.\gradlew.bat :external:imagedecoder-houri:library:assembleDebug --no-configuration-cache
# Verify fix: .cxx/.../fk/src/ep_libiconv/srclib/fcntl.c starts with #ifndef F_SETFD
# and .cxx/.../fk/src/ep_libiconv/Makefile has "# cd src &&"
```

## Why this was needed

- `libiconv` `PATCH_COMMAND` stamp (`ep_libiconv-patch`) wasn't invalidated when `patch_iconv.sh` changed, and `URL` had no `URL_HASH`.
- `to_msys_path` emitted `/c/` (MSYS) but WSL bash needed `/mnt/c/` (or vice-versa), so `CC` was not found and `configure` fell back to host `gcc` (`x86_64` instead of `aarch64`), or later correctly cross-compiled but tried to build `src/iconv` (host tool) for Android and failed with `rpl_*` link errors. Fixed in `f480abb` + `9a7fe0d` (configure helper + skip `src/`).
