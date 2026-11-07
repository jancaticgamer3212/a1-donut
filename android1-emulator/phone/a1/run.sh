#!/system/bin/sh
# Boot the Android 1.6 guest.  The framebuffer is exported over VNC on
# 127.0.0.1:5900; touch and keyboard come back through usb-tablet/usb-kbd.
D=${A1_DIR:-/data/local/a1}
R=$D/rootfs
export TMPDIR=/data/local/tmp
unset LD_PRELOAD
exec "$R/lib/arm-linux-gnueabihf/ld-linux-armhf.so.3" --library-path "$R/lib/arm-linux-gnueabihf" \
  "$R/usr/bin/qemu-system-arm" \
  -L "$R/usr/share/qemu" \
  -M versatilepb -m 256 -cpu arm926 \
  -kernel "$D/guest/zImage" -initrd "$D/guest/initramfs.cpio.gz" \
  -append "console=ttyAMA0 loglevel=7" \
  -display vnc=127.0.0.1:0 \
  -qmp tcp:127.0.0.1:4444,server,nowait \
  -chardev socket,id=a1ser,host=127.0.0.1,port=5555,server=on,wait=off,logfile=$D/serial.log \
  -serial chardev:a1ser \
  -device pci-ohci,addr=0x10 -device usb-kbd -device usb-tablet \
  "$@"
