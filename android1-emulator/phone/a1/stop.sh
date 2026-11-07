#!/system/bin/sh
# The process comm is the loader name (qemu is exec'd through ld-linux).
found=0
for d in /proc/[0-9]*; do
  c=$(cat "$d/comm" 2>/dev/null)
  case "$c" in
    ld-linux-armhf*|qemu-system-arm*)
      kill -9 "${d#/proc/}" 2>/dev/null && found=1
      ;;
  esac
done
echo "stopped $found"
