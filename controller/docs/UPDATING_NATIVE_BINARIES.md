# Updating native binaries (aria2, proot, tar, …)

The pinned native binaries are defined by **three** files that must always stay in sync.
Update them **together, in the same commit** whenever you bump to a new binaries release.

| File | Role |
|------|------|
| `controller/binary_version.txt` | The pinned release tag (e.g. `binaries-2026-07-05_05-38`). |
| `controller/app/src/main/assets/ninja_manifest.json` | Expected SHA256 of every binary for that tag. The build audits against this. |
| `controller/app/src/main/assets/cacert.pem` | CA bundle shipped with the release (update only if it changed). |

## How to bump

1. Set the new tag in `controller/binary_version.txt`.
2. Run the sync so the manifest (and cacert) come from that exact release:
   ```bash
   cd controller && ./gradlew :app:syncNativeArtifacts
   ```
3. Commit the three files together:
   ```bash
   git add controller/binary_version.txt \
           controller/app/src/main/assets/ninja_manifest.json \
           controller/app/src/main/assets/cacert.pem
   git commit -m "chore: bump native binaries to <tag>"
   ```

## Why (background)

`ninja_manifest.json` is the integrity source of truth and is **version-controlled on purpose**
(supply-chain traceability). If `binary_version.txt` moves without the manifest, the on-disk
binaries (gitignored) no longer match the committed manifest, and the SHA256 audit in
`syncNativeArtifacts` fails on the next build (first offender is usually `libaria2c.so`),
forcing a re-download. PR #150 bumped only `binary_version.txt`; this is the miss this doc
prevents. The CI check `binary-bump-consistency` enforces it.
