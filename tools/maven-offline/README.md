# maven-offline (K2GO-437)

> Status: KEPT DEV TOOL, NOT SHIPPED. The offline build path does not use this.
> Code on the Go already downloads its own offline build environment (Android SDK,
> terminal bootstrap, Gradle, and a prebuilt localMvnRepository.zip) from
> appdevforall.org/dev-assets and builds on device with the network disabled, so a
> separate Maven repository is redundant. This tool is kept under tools/ because it
> works and is validated: useful if that model changes, or for a desktop offline build.

A download machine. It builds one offline Maven repository that holds every
dependency the builds need: Knowledge to Go, Code on the Go, and the add-ons.
A device or a laptop on the K2Go local network then builds those projects with
the network off.

This folder is the PRODUCER tool only. The Ansible role that installs and serves
the repository, and the Code on the Go consumer setting, are separate tickets.

## Contract

- Output layout: standard Maven2 (`<group>/<artifact>/<version>/<file>` with
  `.sha1`/`.md5`). This is what nginx serves and what a Gradle `maven { url ... }`
  repository consumes. It is NOT the Gradle internal cache layout.
- Served over HTTP by the box nginx at a new path, for example
  `http://<box-ip>:8085/maven-offline/`. No new port: it is one more path.
- One shared repository for all three projects (union). About 1.1 to 1.2 GiB.
  Separate per-project repositories are not worth it.
- Architecture: about 99.8 percent of the repository is architecture-neutral JVM
  bytecode, so one repository serves ARM devices and desktop build hosts. Only
  `aapt2` and `brotli4j` have per-OS files.
- Build hosts in scope: on-device (ARM Android), Linux, Windows. MacOS deferred.
  On-device (ARM) does not need the Maven `aapt2`: Code on the Go ships its own.

## Pipeline

1. Resolve. For each project, run `resolveAllDeps` (see `gradle/resolve-all.init.gradle`)
   against an isolated Gradle user home. This forces a download of every resolvable
   configuration plus the buildscript/plugin classpath into that home's module cache.
2. Reshape. Convert the module cache (`caches/modules-2/files-2.1`, content-addressed)
   into Maven2 layout under `out/repo`.
3. Extras. Add the task-time tools a plain resolve misses: R8/D8, `aapt2` for Linux
   and Windows, `brotli4j` native for Linux and Windows. See `config/extra-artifacts.tsv`.
4. Union and checksums. Merge all three into one tree (dedup by Maven path) and write
   `.sha1`/`.md5` for every file.
5. Smoke test. Build a target with the network off, using only `out/repo` as the single
   repository. This is the acceptance proof that the repository is complete.

## Why not the suggested plugin

The suggested `io.github.yubyf.maven-offline` 1.0.4 writes zero artifacts on our
Gradle versions (8.8 and 8.14): it reports "No effective repositories found" and
skips the download. So the producer uses direct Gradle resolution instead.

## Usage

    ./build-maven-offline.sh            # resolve + reshape + extras + checksums
    ./build-maven-offline.sh --smoke    # also run the offline build smoke test

Notes:
- Run on an online host (the producer must reach Maven Central and Google Maven).
- Linux is the canonical producer host (CI). On Windows, run from a shell where a
  Gradle daemon can open a loopback socket; `--no-daemon` is used to avoid that.
- `out/` and the per-run Gradle homes are generated, not committed (see `.gitignore`).

## Sizes and method

See the local study `maven-offline-study/REPORT.md` for the measured sizes, the
common trunk, and the per-OS slivers.
