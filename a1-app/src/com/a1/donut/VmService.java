package com.a1.donut;

import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/* Owns the QEMU process. Everything lives in the app's private files
 * directory, so no root or /data/local access is required. */
public class VmService extends Service {
    private static final String TAG = "A1VM";
    public static final String ACTION_START = "com.a1.donut.action.START";
    public static final String ACTION_STOP = "com.a1.donut.action.STOP";

    public static volatile boolean running;
    public static volatile String phase = "idle";
    public static volatile File serialLog;

    private final AtomicBoolean starting = new AtomicBoolean(false);
    private Process qemu;
    private Thread unlockThread;
    private Thread pumpThread;
    private PowerManager.WakeLock wakeLock;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopVm();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!running && starting.compareAndSet(false, true)) {
            startForeground(1, notification("Preparing Android 1.6\u2026"));
            new Thread(new Runnable() {
                @Override
                public void run() {
                    boot();
                }
            }, "a1-boot").start();
        }
        return START_NOT_STICKY;
    }

    private void boot() {
        try {
            phase = "preparing";
            File root = prepare();
            phase = "booting";
            startForeground(1, notification("Booting Android 1.6\u2026"));

            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "a1:vm");
            wakeLock.acquire();

            phase = "booting";
            launch(root);
            running = true;
            phase = "running";
            startForeground(1, notification("Android 1.6 running"));

            unlockThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    unlockGuest();
                }
            }, "a1-unlock");
            unlockThread.setDaemon(true);
            unlockThread.start();
        } catch (Throwable t) {
            Log.e(TAG, "boot failed", t);
            String msg = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            stopVm();
            phase = "failed: " + msg;
            stopSelf();
        } finally {
            starting.set(false);
        }
    }

    private File prepare() throws IOException {
        File root = new File(getFilesDir(), "a1");
        File marker = new File(root, ".extracted");
        if (!marker.exists()) {
            deleteRecursive(root);
            if (!root.mkdirs() && !root.isDirectory()) throw new IOException("cannot create " + root);
            copyAsset("rootfs", new File(root, "rootfs"));
            copyAsset("guest", new File(root, "guest"));
            new FileOutputStream(marker).close();
        }
        File loader = new File(root, "rootfs/lib/arm-linux-gnueabihf/ld-linux-armhf.so.3");
        File qemuBin = new File(root, "rootfs/usr/bin/qemu-system-arm");
        if (!loader.isFile() || !qemuBin.isFile()) throw new IOException("assets missing");
        if (!loader.setExecutable(true, false)) throw new IOException("chmod loader failed");
        if (!qemuBin.setExecutable(true, false)) throw new IOException("chmod qemu failed");
        serialLog = new File(root, "serial.log");
        //noinspection ResultOfMethodCallIgnored
        serialLog.delete();
        return root;
    }

    public static String serialTail(int max) {
        File f = serialLog;
        if (f == null || !f.isFile()) return "";
        java.io.RandomAccessFile raf = null;
        try {
            raf = new java.io.RandomAccessFile(f, "r");
            long len = raf.length();
            int n = (int) Math.min(max, len);
            raf.seek(len - n);
            byte[] buf = new byte[n];
            raf.readFully(buf);
            StringBuilder sb = new StringBuilder();
            for (byte b : buf) sb.append(b < 32 || b > 126 ? ' ' : (char) b);
            String s = sb.toString().replaceAll(" +", " ").trim();
            return s.length() > max ? s.substring(s.length() - max) : s;
        } catch (IOException e) {
            return "";
        } finally {
            if (raf != null) {
                try {
                    raf.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private void copyAsset(String path, File dest) throws IOException {
        String[] children = getAssets().list(path);
        if (children != null && children.length > 0) {
            if (!dest.mkdirs() && !dest.isDirectory()) throw new IOException("mkdir " + dest);
            for (String child : children) {
                copyAsset(path + "/" + child, new File(dest, child));
            }
        } else {
            File parent = dest.getParentFile();
            if (parent != null && !parent.mkdirs() && !parent.isDirectory()) {
                throw new IOException("mkdir " + parent);
            }
            InputStream in = getAssets().open(path);
            OutputStream out = new FileOutputStream(dest);
            try {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } finally {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
                out.close();
            }
        }
    }

    private void launch(File root) throws IOException {
        final Process prev = qemu;
        if (prev != null) {
            prev.destroy();
            for (int i = 0; i < 15 && alive(prev); i++) pause(200);
        }
        File lib = new File(root, "rootfs/lib/arm-linux-gnueabihf");
        List<String> cmd = new ArrayList<String>();
        cmd.add(new File(lib, "ld-linux-armhf.so.3").getAbsolutePath());
        cmd.add("--library-path");
        cmd.add(lib.getAbsolutePath());
        cmd.add(new File(root, "rootfs/usr/bin/qemu-system-arm").getAbsolutePath());
        cmd.add("-L");
        cmd.add(new File(root, "rootfs/usr/share/qemu").getAbsolutePath());
        cmd.add("-M");
        cmd.add("versatilepb");
        cmd.add("-m");
        cmd.add("256");
        cmd.add("-cpu");
        cmd.add("arm926");
        cmd.add("-kernel");
        cmd.add(new File(root, "guest/zImage").getAbsolutePath());
        cmd.add("-initrd");
        cmd.add(new File(root, "guest/initramfs.cpio.gz").getAbsolutePath());
        cmd.add("-append");
        cmd.add("console=ttyAMA0 loglevel=7");
        cmd.add("-display");
        cmd.add("vnc=127.0.0.1:0");
        cmd.add("-qmp");
        cmd.add("tcp:127.0.0.1:4444,server,nowait");
        cmd.add("-chardev");
        cmd.add("socket,id=a1ser,host=127.0.0.1,port=5555,server=on,wait=off,logfile="
                + new File(root, "serial.log").getAbsolutePath());
        cmd.add("-serial");
        cmd.add("chardev:a1ser");
        cmd.add("-device");
        cmd.add("pci-ohci,addr=0x10");
        cmd.add("-device");
        cmd.add("usb-kbd");
        cmd.add("-device");
        cmd.add("usb-tablet");

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(root);
        pb.environment().remove("LD_PRELOAD");
        pb.environment().put("TMPDIR", getCacheDir().getAbsolutePath());
        pb.redirectErrorStream(true);
        qemu = pb.start();

        final InputStream in = qemu.getInputStream();
        final File log = new File(root, "qemu.log");
        pumpThread = new Thread(new Runnable() {
            @Override
            public void run() {
                OutputStream out = null;
                try {
                    out = new FileOutputStream(log);
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        out.flush();
                    }
                } catch (IOException ignored) {
                } finally {
                    try {
                        in.close();
                    } catch (IOException ignored) {
                    }
                    if (out != null) {
                        try {
                            out.close();
                        } catch (IOException ignored) {
                        }
                    }
                    if (running) {
                        running = false;
                        phase = "failed: QEMU stopped unexpectedly";
                    }
                }
            }
        }, "a1-log");
        pumpThread.setDaemon(true);
        pumpThread.start();

        pause(2000);
        if (!alive(qemu)) {
            String tail = tail(new File(root, "qemu.log"));
            throw new IOException(tail.isEmpty() ? "QEMU exited at startup" : tail);
        }
    }

    private static boolean alive(Process p) {
        try {
            p.exitValue();
            return false;
        } catch (IllegalThreadStateException e) {
            return true;
        }
    }

    private static void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    private static String tail(File f) {
        if (!f.isFile()) return "";
        StringBuilder sb = new StringBuilder();
        java.io.RandomAccessFile raf = null;
        try {
            raf = new java.io.RandomAccessFile(f, "r");
            long len = raf.length();
            raf.seek(Math.max(0, len - 400));
            byte[] buf = new byte[(int) Math.min(400, len)];
            raf.readFully(buf);
            for (byte b : buf) sb.append(b == '\n' || b == '\r' ? ' ' : (char) b);
        } catch (IOException ignored) {
        } finally {
            if (raf != null) {
                try {
                    raf.close();
                } catch (IOException ignored) {
                }
            }
        }
        String s = sb.toString().trim();
        return s.length() > 300 ? s.substring(s.length() - 300) : s;
    }

    private void unlockGuest() {
        for (int i = 0; i < 60 && running; i++) {
            KeyInjector.send("svc power stayon true; input keyevent 82");
            try {
                Thread.sleep(10000);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void stopVm() {
        running = false;
        phase = "stopped";
        if (unlockThread != null) {
            unlockThread.interrupt();
            unlockThread = null;
        }
        final Process p = qemu;
        qemu = null;
        if (p != null) {
            p.destroy();
            Thread killer = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException ignored) {
                    }
                    p.destroyForcibly();
                }
            }, "a1-kill");
            killer.setDaemon(true);
            killer.start();
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        stopForeground(true);
    }

    @Override
    public void onDestroy() {
        stopVm();
        super.onDestroy();
    }

    private Notification notification(String text) {
        return new Notification.Builder(this)
                .setContentTitle("Android 1.6")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.sym_def_app_icon)
                .setOngoing(true)
                .build();
    }

    private static void deleteRecursive(File f) {
        if (f == null) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursive(k);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
