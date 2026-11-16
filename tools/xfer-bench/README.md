# xfer-bench

Test APK for K2GO-576: how fast two phones move data over TCP (Java copy loops) and over a
USB cable (Android Open Accessory). It is a measurement tool, not part of K2Go. It is never
published. Results: <https://appdevforall.atlassian.net/wiki/spaces/KTGPD/pages/1120501761>.

Build (from this folder, in a host terminal):

```
.\gradlew.bat assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Drive it with adb. Each result goes to logcat (tag `XferBench`) and to `files/results.txt`.

| Command | Extras |
| --- | --- |
| `tcp-recv` | `port` (5301), `method` (`kernel`, `direct`, `stream`), `sink` (`null`, `int`, `ext`), `bufkb` (256) |
| `tcp-send` | `host`, `port`, `method`, `pattern` (`zeros`, `ones`, `alt`), `mb` (long), `src` (`pattern`, `int`, `ext`) |
| `prep` | `at` (`int`, `ext`), `pattern`, `mb`: writes `src.bin` for a file send |
| `aoa-host` | `dir` (`S` host sends, `R` host receives), `pattern`, `mb`, `buf` (bytes per bulk transfer) |
| `aoa-acc` | `buf`: serve the host (also starts by itself when the host switches the phone to accessory mode) |

Example:

```
adb shell am start -n org.appdevforall.xferbench/.MainActivity --es cmd tcp-recv --es method kernel --es sink int
adb shell am start -n org.appdevforall.xferbench/.MainActivity --es cmd tcp-send --es host 192.168.1.66 --el mb 1024
```

`method=kernel` uses `FileChannel.transferTo` (sendfile) on the sender and `transferFrom` on the
receiver. `sink=int` writes to app-private storage, `sink=ext` to the app folder on shared
storage (FUSE). For AOA, run `aoa-host` on the phone that is the USB host (`dumpsys usb`) and
accept the USB dialogs on both phones.
