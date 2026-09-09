# WSL Tests Wrapper

Convenience wrapper that delegates to the full suite in the submodule:

```
external/imagedecoder-houri/tests/wsl/run_all.sh
```

Run from repo root:

```bash
bash scripts/wsl-tests/run.sh
# or directly
bash external/imagedecoder-houri/tests/wsl/run_all.sh
```

No Gradle required. See `external/imagedecoder-houri/tests/README.md` for suite details.
