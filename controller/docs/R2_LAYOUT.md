# Cloudflare R2 layout

Where each artifact lives in R2, who writes it, and whether it is replaced or kept.
Update this file when a workflow adds a new prefix.

## Rules for new keys

- One prefix per kind of content. Do not add files to a bucket root.
- Content that changes in place (a "latest" catalog) uses a fixed key that the workflow
  overwrites. Content that must stay (a build) goes under a versioned path,
  `<prefix>/<version>/`, and is never overwritten.
- Each file has one checksum: a sidecar (`.sha256`, or the `.md5` that Code on the Go
  publishes) or a manifest that carries it (`catalogs/<name>.manifest.json`). Do not add a
  second one.
- Upload the data before the file that points to it (pointer last), so a client never reads
  a pointer to a partial upload.

## Bucket `k2go-apk-repo`

Public at `https://k2go-download.appdevforall.org`.

| Key | Content | Written by | Lifecycle |
|-----|---------|------------|-----------|
| `K2Go-<version>-<abi>-release.apk`, `K2Go-<version>-play-release.aab` (root) | Website APKs and the Play AAB | `android-release-build.yml`, `android-r2-republish.yml` | Overwritten per release |
| `update.json` (root) | OTA manifest for the standard flavor | `android-release-build.yml` | Overwritten per release |
| `catalogs/<file>`, `catalogs/<name>.manifest.json` | Content catalogs (Kolibri, Kiwix, Maps) and their manifests (sha256, url) | `publish-catalogs.yml` (weekly) | Overwritten |
| `binaries/<tag>/termux-binaries-latest.zip` (+ `.sha256`) | Native binaries archive, one folder per `binaries-*` release | `binaries-lifecycle.yml` (K2GO-506) | Immutable, never deleted |

The APKs at the root predate these rules. They stay there because the website and
`update.json` link to them.

## Bucket `k2go-rootfs`

Public at the R2 dev URL in the repository variable `ROOTFS_PUBLIC_BASE`
(the default is in `bake-rootfs.yml`).

| Key | Content | Written by | Lifecycle |
|-----|---------|------------|-----------|
| `k2go_*_<tier>_<abi>.tar.gz` (+ `.meta4`, `.sha256`, `.installed`, `.torrent`) | Dated rootfs images | `bake-rootfs.yml` | Newest 7 per tier and ABI; older ones are deleted |
| `latest_<tier>_<abi>.meta4`, `latest_<tier>_<abi>.installed` | Stable pointers to the current image | `bake-rootfs.yml` | Overwritten, last |
| `build-<tier>-<abi>.log`, `PUBLISH_QUEUE.tsv` | Installer logs (input for the size catalogs) and the publish queue | `bake-rootfs.yml` | Overwritten |
| `proot-distro-v<version>/` | Debian base from the proot-distro release (+ `.sha256`) | `bake-rootfs.yml` (seeded once) | Static |

## Code on the Go build assets

Public at `https://dev-assets.appdevforall.org` (K2GO-474). `release/` and `debug/` hold the
Code on the Go build environment, each file with an `.md5` sidecar. No workflow in this
repository writes it. The box mirrors the `release/` set (role `code_assets`).
