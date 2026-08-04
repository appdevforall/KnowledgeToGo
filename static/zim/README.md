# static/zim — bundled ZIM assets

Small ZIM files that ship **with the K2Go repo** and are copied into
`/library/zims/content` at install time (see `install_welcome_zim` in the
top-level `iiab-android` wrapper).

## k2go_welcome.zim

A tiny multilingual welcome page (the K2Go logo ringed by "Welcome" in 33
languages; the footer tagline auto-detects the device language via
`navigator.language`). It replaces the upstream placeholder `test.zim` that
iiab/iiab's kiwix role would otherwise install, so a fresh box always opens on a
branded welcome instead of a blank library.

`*.zim` is marked `binary` in `.gitattributes`. If the file ever grows large,
consider Git LFS.
