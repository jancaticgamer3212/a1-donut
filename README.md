# a1-donut

A self-contained Android app that runs **Android 1.6 (Donut)** in QEMU and displays it
on the phone itself. No root, no separate VNC client.

## What you get

- **Start / Stop** – a foreground service unpacks the bundled QEMU binary and Donut
  images into the app's private storage and boots the guest.
- **Built-in viewer** – a small RFB (VNC) client in the app decodes the guest
  framebuffer and draws it, so nothing else needs to be installed.
- **Back / Menu / Home bar** at the bottom of the screen – taps are injected into the
  guest through its serial console (`input keyevent 4/82/3`).

First boot to the Donut home screen takes about 2–3 minutes on a 2015 phone
(qemu-system-arm is emulating an ARM926 at full software speed).

## Requirements

- Android 5.0+ on an `armeabi-v7a` device (built and tested on a Galaxy S6 edge,
  Android 5.1.1)
- ~120 MB of free storage for the unpacked images

## Install

Download `a1-donut.apk` from the [latest release](../../releases/latest), sideload it,
open it and press **Start**.

## Build

No Gradle. `build.sh` drives `aapt2`, `javac`, `d8`, `zipalign` and `apksigner`
directly and needs JDK 8+ plus Android SDK build-tools 34 / platform 32:

```sh
SDK=/c/android-sdk bash a1-app/build.sh
```

Output: `a1-app/build/a1-donut.apk` (signed with a generated debug key).

## How it works

`a1-app/src/com/a1/donut/`

| file | role |
| --- | --- |
| `MainActivity.java` | start panel, bottom key bar, status line |
| `VmService.java` | foreground service: unpacks assets, spawns QEMU, pumps its log |
| `VncView.java` | minimal RFB 3.8 client (raw + DesktopSize encodings, 16bpp) |
| `KeyInjector.java` | writes `input keyevent N` to the guest serial console |

QEMU runs entirely as the app's own uid:

```
qemu-system-arm -M versatilepb -m 256 -cpu arm926 \
  -kernel guest/zImage -initrd guest/initramfs.cpio.gz \
  -append "console=ttyAMA0 loglevel=7" \
  -display vnc=127.0.0.1:0 -qmp tcp:127.0.0.1:4444,server,nowait \
  -chardev socket,id=a1ser,host=127.0.0.1,port=5555,server=on,wait=off,logfile=<files>/serial.log \
  -serial chardev:a1ser -device pci-ohci,addr=0x10 -device usb-kbd -device usb-tablet \
  -L rootfs/usr/share/qemu
```

The viewer talks to `127.0.0.1:5900`. Note that an RFB 3.8 server sends a 4-byte
SecurityResult word even when the security type is None — a client that skips it
reads 0x0 for the framebuffer size and every later field is shifted.

The guest console is exposed as a TCP chardev on `127.0.0.1:5555`, which is how the
Back/Menu/Home buttons reach `input keyevent`.

## Repo layout

```
a1-app/                  the Android app (source, manifest, build script)
android1-emulator/
  phone/a1/              rootfs + guest kernel/initramfs and the shell helpers
  kernel/                guest kernel patches (touchscreen, battery)
  tools/                 scripts used to build the Donut rootfs
```

`android1-emulator/guest/` (an extracted copy of the rootfs) is not tracked.
