#!/usr/bin/env python3
"""Adapt the stock Donut init.rc to a generic QEMU guest.

- /system lives in the initramfs, /data stays on the writable rootfs
- services that need goldfish/RIL/Bluetooth hardware are dropped so init
  doesn't restart-loop them
- legacy /sys/power nodes are faked so Donut believes the screen is on
"""
import re
import sys

# libhardware_legacy's set_screen_state() opens wake_lock, wake_unlock and
# state together; if any is missing it fails with ENOENT and
# PowerManagerService never sets SCREEN_ON_BIT, which makes WMS discard every
# EV_ABS event ("screen is off") while still passing BTN_TOUCH through.  The
# 3.10 kernel has no Android wakelock support, so back /sys/power with a
# tmpfs and create the three files the framework expects.  A regular file for
# "state" also keeps a stray "mem" write from suspending the VM.
POWER_FIX = """\
    # Fake the legacy wakelock sysfs nodes for frameworks that expect them.
    mount tmpfs tmpfs /sys/power mode=0755
    write /sys/power/state on
    write /sys/power/wake_lock a1
    write /sys/power/wake_unlock a1
    # versatilepb has no radio, so the SIM reads as ABSENT and
    # LockPatternKeyguardView refuses to ever unlock ("stuck on lock screen
    # because sim missing") until the device is provisioned.  An empty
    # keyguard.no_require_sim would mean "require SIM"; setting it disables
    # the check and MENU dismisses the keyguard as on a provisioned device.
    setprop keyguard.no_require_sim 1
"""

DROP_SERVICES = {
    "nexus", "ril-daemon", "bootsound", "dbus", "hcid", "hfag", "hsag",
    "flash_recovery", "vold",
}

DROP_LINE_PATTERNS = [
    r"^\s*mount yaffs2",
    r"^\s*mount rootfs rootfs / ro remount",
]


def main(src, dst):
    out = []
    skip_service = False
    power_fix_done = False
    for line in open(src, encoding="utf-8", errors="replace"):
        stripped = line.strip()
        if (not power_fix_done
                and stripped == "chown radio system /sys/power/state"):
            out.append(POWER_FIX)
            power_fix_done = True
        if stripped.startswith("service "):
            name = stripped.split()[1]
            skip_service = name in DROP_SERVICES
            if skip_service:
                continue
        elif skip_service:
            # service options are indented; a blank line or a new top-level
            # section ends the block
            if line[:1] in (" ", "\t"):
                continue
            skip_service = False
        if any(re.match(p, line) for p in DROP_LINE_PATTERNS):
            continue
        out.append(line)
    open(dst, "w", encoding="utf-8").write("".join(out))


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
