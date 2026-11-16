# ADR-K2GO-576 : Clone adds a USB cable path (Android Open Accessory)

- Status: Proposed
- Date: 2026-11-16
- Ticket: K2GO-576
- Author: AppDevForAll
- Evidence: [Phone to phone transfer speed: test results](https://appdevforall.atlassian.net/wiki/spaces/KTGPD/pages/1120501761)
  (measurements with the `tools/xfer-bench` test APK)

## Decision in one line

Clone keeps rsync as its only transfer engine. A USB-C cable between the two phones carries a
second rsync session over an Android Open Accessory (AOA) byte stream, in parallel with the
existing Wi-Fi session, and the library is split between the two.

## Context (facts)

- Clone today: the sender starts a read-only rsync daemon (`RsyncManager.startServer`), the
  receiver pulls with `rsync://user@host:port/module` (`RsyncConfig.clientArgs`: `-a --delete
  --partial`). The link is the same Wi-Fi router, or a hotspot that the app starts by itself
  (`LocalHotspotManager`: a Wi-Fi Direct group, fallback LocalOnlyHotspot). The hotspot band
  default is Automatic (`HotspotBand.DEFAULT`, K2GO-561). The transport port is
  `sync/transport/TransportEngine`.
- Measured on a OnePlus 7T, a Samsung A16 and an OPPO CPH2557 (no root):
  - The bottleneck is the radio of the slower phone. Low-end phones have Wi-Fi 5 with one
    stream (433 Mbps): about 42 MB/s on a 5 GHz phone hotspot, 33-40 MB/s through a router,
    8-9 MB/s on 2.4 GHz.
  - AOA between two phones works without root on all three phones. On USB 2.0 it carries
    19-25 MB/s. The cable type made no difference (four cables, up to 2 m).
  - **A 5 GHz hotspot plus AOA at the same time gave 61-66 MB/s, about +50% over the hotspot
    alone**, on two pairs: 7T to A16, and A16 to OPPO (two low-end MediaTek phones). The two
    paths add up (about 95% of the sum): the hotspot speed did not change with AOA running.
  - The USB host role is decided at plug-in and the app cannot change it. A MediaTek phone as
    USB host accepts only 16 KB bulk transfers and receives at about 7 MB/s; as accessory it
    receives at about 20 MB/s. The 7T as host accepted 128 KB transfers.
  - USB tethering (an IP link over the cable) does not work without root on these phones.
    A USB Ethernet adapter is slower than the phone's own Wi-Fi.
  - rsync reaches about 77% of a fast link on the real library (64,625 files); the loss is the
    cost per file.
- AOA is a public Android API (`android.hardware.usb`, since Android 3.1). It needs no
  permission in the manifest, only the optional features `usb.host` and `usb.accessory`, and a
  one-time user dialog on each phone.

## Decision

1. **rsync stays the engine on both paths.** The cable path does not get its own file
   protocol. Integrity, `--partial` resume, the exclude list, auth and progress stay as they are.
2. **A local TCP relay carries rsync over AOA.** On the receiver, the app listens on
   `127.0.0.1:<port>` and forwards the bytes into the AOA stream. On the sender, the app reads
   the AOA stream and connects to its own rsync daemon on `127.0.0.1`. The rsync client uses
   `rsync://user@127.0.0.1:<port>/module`. No change to `rsyncd.conf` or to the rsync argv.
3. **A small framed protocol on the AOA stream.** Frames carry a type (open, data, close,
   reset), a channel id and a length, so that the dry-run and the transfer can use the cable
   one after the other, and so that a new session can discard old bytes (the test APK showed
   that bytes from a failed run stay in the link).
4. **The library is split between the two paths by a shared work queue.** The dry-run gives the
   file list. It is cut into chunks of similar size. The Wi-Fi rsync and the cable rsync each
   take the next chunk (`--files-from`) until the queue is empty. A faster path takes more
   chunks; a path that fails puts its chunk back in the queue.
5. **The USB roles are detected, not chosen.** The phone that is the USB host runs the AOA host
   side. The sender should be the host: when the receiver is the host, the cable still works
   but carries less (about 7 MB/s on MediaTek). The host starts with 128 KB transfers and drops
   to 16 KB on the first bulk error.
6. **Without Wi-Fi, the hotspot asks for 5 GHz.** Automatic stays the default when the phone is
   on a Wi-Fi network (K2GO-561). When the phone has no Wi-Fi network, Clone asks for 5 GHz and
   falls back to 2.4 GHz only when 5 GHz fails.
7. **The cable path is optional.** With no cable, Clone works as today. With a cable and no
   Wi-Fi, the cable carries the whole library.
8. **Both sides know the state, with rsync's own daemon hooks (no wrapper around the binary).**
   Today only the receiver knows the result (it reads the rsync output and exit code); the
   sender's daemon logs to `/dev/null`. The sender's `rsyncd.conf` adds:
   - `post-xfer exec`: the daemon runs a command after each transfer with `RSYNC_EXIT_STATUS`,
     `RSYNC_PID`, `RSYNC_HOST_ADDR` and `RSYNC_USER_NAME` in its environment. The command appends
     one line to a status file in the app cache.
   - `transfer logging = yes` with a `log format` of file name and bytes: one line per file sent,
     to a log file in the app cache. This gives the sender its own progress.
   The app watches both files with a `FileObserver` (no polling). On the cable path the relay
   also counts the bytes it forwards.

## Lifecycle (who starts, who stops, what if the process dies)

- The AOA session belongs to the Clone foreground service on each side (sender:
  `CloneShareService`; receiver: the service that runs the pull). It starts on the
  `USB_ACCESSORY_ATTACHED` intent or when the host finds the accessory, and stops on detach,
  on "Stop sharing", or when the transfer ends.
- The relay and its rsync child stop with the session. A chunk in progress is put back in the
  queue; rsync `--partial` keeps the partial file for the next attempt.
- The status file and the transfer log belong to the sender's Clone service: it creates them
  when the daemon starts and deletes them when the daemon stops. A stale file from a dead
  process is deleted at the next daemon start.
- If the app process dies, both sides lose the session. The cable then shows no app on the
  accessory side and the host write fails; the next Clone start sends a reset frame first. No
  state is persisted for the cable path.

## Alternatives considered

- **USB tethering:** no root path on the tested phones (vendor gadget type, host drivers and the
  Ethernet name filter differ). Rejected.
- **MTP through the system file picker:** works, but about 10 MB/s and needs the user to pick
  files. Rejected.
- **A cable-only protocol without rsync:** more code to own for integrity and resume, against
  the rule to prefer proven tools. Rejected.
- **Wi-Fi on 2.4 GHz and 5 GHz at the same time ("software MLO"):** needs dual station
  concurrency, which most low-end phones do not have, and 2.4 GHz adds only 8-9 MB/s. One radio
  that splits time between two channels was slower in K2GO-561. Not now.
- **A tar or ZIP archive mounted as a file system on the receiver:** the library is a live Linux
  tree (databases, services) in app-private storage; mounting an archive needs FUSE (root).
  Not applicable.
- **USB Ethernet adapter, Wi-Fi plus Ethernet:** slower than the phone's own Wi-Fi; about +10%
  together on the 7T. Rejected.

## Consequences

- New code: the AOA session (host and accessory), the relay, the frame protocol, the chunk queue
  and a second rsync client. They live in a feature package under `sync/` with a domain part
  (frames, chunk planning) that is pure JVM and unit-tested.
- New manifest entries: the `USB_ACCESSORY_ATTACHED` intent filter with an accessory filter
  resource, and the optional `usb.host` / `usb.accessory` features. No new permission.
- Users see a one-time USB dialog on each phone.
- Risks to watch: the OPPO restarted once right after its hotspot was turned off at the end of a
  test; heat and battery with two paths at full speed; the accessory app must stay in the
  foreground or in a foreground service to read.

## Plan

1. Cable-only Clone: AOA session, relay, frames; one rsync session over the cable.
2. Wi-Fi plus cable: the chunk queue and two rsync sessions in parallel.
3. Measure with real files on two low-end phones (storage writes on the receiver), then tune the
   chunk size and the transfer size.

## Open questions

- AOA speed between two USB 3 phones (not measured; only one USB 3 phone was available).
- How much the receiver's storage writes reduce the total at 60+ MB/s.
- Whether the hotspot plus cable path should be the default when both phones support it, or an
  option.
