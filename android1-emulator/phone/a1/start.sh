#!/system/bin/sh
# Start the emulator detached; logs go to /data/local/a1/run.out.
cd /data/local/a1 || exit 1
chmod 0755 rootfs/lib/arm-linux-gnueabihf/ld-linux-armhf.so.3 rootfs/usr/bin/qemu-system-arm run.sh
setenforce 0 2>/dev/null
sh stop.sh >/dev/null 2>&1
sleep 1
rm -f run.out serial.log unlock.out
# su sends SIGHUP to its process group on exit, which QEMU treats as fatal;
# spawn puts the emulator in its own session first.
nohup ./spawn /system/bin/sh /data/local/a1/run.sh >run.out 2>&1 &
echo "started pid $!"
# The guest stops on its lock screen; inject MENU over the guest serial
# console until it is up and the home screen shows.
nohup ./spawn /system/bin/sh /data/local/a1/unlock.sh >unlock.out 2>&1 &
echo "unlock helper pid $!"
