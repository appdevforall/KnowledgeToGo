# Pre-build tests (K2GO-567)

Fast, native tests that run BEFORE the rootfs bake (no proot, no root), with our
upstream patches applied. If they fail, the bake must not run. The workflow is
`.github/workflows/prebuild-tests.yml`.

Layout: one directory per area/feature, so files never mix. Each test owns its
directory and fixtures.

    tests/
      maps/fqr/          - maps Full Quality Region (FQR) contract (K2GO-566)
      kiwix/arch/        - kiwix-tools build follows the userland arch (32-bit libraries)
      kiwix/downloads/   - (future) Kiwix binary download + R2 rescue

Conventions:

- A test file is `test_*.py` (run by `run-contract-tests.sh`), or a dash-node unit
  test under `static/dashboard` (run by `npm test`).
- A CONTRACT test checks the REAL upstream artifact (the version the bake uses),
  not a frozen fixture, so upstream drift is caught. It reads the artifact from an
  env var (for example `TILE_EXTRACT_PY`) and SKIPS when the var is unset, so it is
  safe to run anywhere; the pre-build workflow sets the var before it runs.
- Keep tests native: pure Python stdlib or `npm`. No proot, no root, no network
  unless the test owns a cheap, pinned fetch.
