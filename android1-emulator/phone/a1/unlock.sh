#!/system/bin/sh
# Dismiss the guest lock screen: Donut's LockScreen only accepts Android
# KEYCODE_MENU (82), which a VNC viewer cannot send (its keysyms land as
# SEARCH).  The guest serial console is a root shell, so inject MENU from
# there and keep the guest display awake.  Runs for ten minutes; MENU on the
# home screen is inert, so the extra presses are harmless.
D=/data/local/a1
n=0
while [ $n -lt 60 ]; do
  $D/pokeser -t 1 "svc power stayon true; input keyevent 82" && echo "poke $n ok"
  n=$((n + 1))
  sleep 10
done
