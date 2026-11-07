package com.a1.donut;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;

/* Minimal RFB 3.8 client: the guest framebuffer is served by QEMU on
 * 127.0.0.1:5900, raw encoding only, 16bpp RGB565, plus pointer events. */
public class VncView extends View {

    public interface Listener {
        void onVncConnected();
        void onVncDisconnected();
    }

    private static volatile boolean connected;
    private static volatile String status = "viewer idle";
    private static volatile int frames;
    private static volatile long lastFrameAt;

    private final Object lock = new Object();
    private final Object writeLock = new Object();
    private final Listener listener;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);

    private Bitmap bitmap;
    private ByteBuffer frame;
    private int fbW, fbH;
    private volatile float scale = 1f, offX, offY;
    private volatile OutputStream out;
    private volatile Socket socket;
    private volatile boolean running;
    private volatile int gen;
    private volatile Thread io;

    public VncView(Context context, Listener listener) {
        super(context);
        this.listener = listener;
    }

    public static boolean isConnected() {
        return connected;
    }

    public static String status() {
        return status;
    }

    /** True once the guest has painted a while and then gone quiet, i.e. boot settles. */
    public static boolean guestSettled() {
        return frames >= 20 && System.currentTimeMillis() - lastFrameAt > 5000;
    }

    public void connect() {
        if (running) return;
        running = true;
        frames = 0;
        lastFrameAt = System.currentTimeMillis();
        final int my = ++gen;
        io = new Thread(new Runnable() {
            @Override
            public void run() {
                loop(my);
            }
        }, "a1-rfb");
        io.setDaemon(true);
        io.start();
    }

    public void disconnect() {
        boolean wasRunning = running;
        running = false;
        gen++;
        closeQuietly(socket);
        socket = null;
        out = null;
        Thread t = io;
        io = null;
        if (t != null) {
            try {
                t.join(1500);
            } catch (InterruptedException ignored) {
            }
        }
        if (wasRunning) notifyDisconnected();
    }

    private void loop(int my) {
        while (running && my == gen) {
            Socket s = null;
            try {
                status = "connecting";
                s = new Socket();
                s.connect(new InetSocketAddress("127.0.0.1", 5900), 1000);
                s.setTcpNoDelay(true);
                socket = s;
                session(s, my);
            } catch (Exception e) {
                String m = e.getMessage();
                status = "retry: " + (m == null ? e.getClass().getSimpleName() : m);
            } finally {
                closeQuietly(s);
                if (socket == s) socket = null;
                out = null;
            }
            if (!running || my != gen) break;
            sleepQuietly(1500);
        }
    }

    private void session(Socket s, int my) throws IOException {
        s.setSoTimeout(8000);
        InputStream in = new BufferedInputStream(s.getInputStream(), 1 << 16);
        OutputStream o = new BufferedOutputStream(s.getOutputStream(), 1 << 16);

        byte[] version = new byte[12];
        readFully(in, version, 12);
        writeAll(o, "RFB 003.008\n".getBytes("US-ASCII"));

        int count = in.read();
        if (count <= 0) throw new IOException("no security types");
        byte[] types = new byte[count];
        readFully(in, types, count);
        boolean none = false;
        for (byte t : types) if (t == 1) none = true;
        if (!none) throw new IOException("server requires authentication");
        writeAll(o, new byte[]{1, 1}); // None, shared

        // RFB 3.8 servers reply with a SecurityResult word even for type None;
        // skipping it shifts every following field by four bytes.
        int result = readS32(in);
        if (result != 0) {
            long reasonLen = readU32(in);
            skipFully(in, reasonLen);
            throw new IOException("security handshake rejected");
        }

        byte[] init = new byte[4];
        readFully(in, init, 4);
        int w = u16(init, 0);
        int h = u16(init, 2);
        byte[] pixfmt = new byte[16];
        readFully(in, pixfmt, 16);
        long nameLen = readU32(in);
        skipFully(in, nameLen);
        if (w <= 0 || h <= 0) {
            // QEMU reports 0x0 until the guest programs the LCD controller.
            status = "connected, guest display not up yet";
            throw new IOException("guest display not ready " + w + "x" + h);
        }
        if (w > 4096 || h > 4096) throw new IOException("bad size " + w + "x" + h);

        synchronized (lock) {
            fbW = w;
            fbH = h;
            frame = ByteBuffer.allocate(w * h * 2);
            bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
        }

        out = o;
        status = "connected " + w + "x" + h;
        writeAll(o, new byte[]{
                0, 0, 0, 0,                 // SetPixelFormat
                16, 16, 0, 1,               // 16bpp, depth 16, little endian, true colour
                0, 31, 0, 63, 0, 31,        // max r/g/b
                11, 5, 0,                   // shifts r/g/b
                0, 0, 0
        });
        writeAll(o, new byte[]{2, 0, 0, 2, 0, 0, 0, 0, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0x21}); // raw + DesktopSize

        connected = true;
        ui.post(new Runnable() {
            @Override
            public void run() {
                listener.onVncConnected();
            }
        });
        postInvalidate();

        int incremental = 0;
        while (running && my == gen) {
            sendFramebufferRequest(o, incremental);
            s.setSoTimeout(15000);
            int type;
            try {
                type = in.read();
            } catch (java.net.SocketTimeoutException epoch) {
                continue;
            }
            if (type < 0) throw new EOFException();
            if (type == 0) {
                in.read(); // padding
                int rects = u16s(in);
                byte[] row = null;
                for (int i = 0; i < rects; i++) {
                    int x = u16s(in), y = u16s(in), rw = u16s(in), rh = u16s(in);
                    int enc = readS32(in);
                    if (enc == 0) {
                        int rowBytes = rw * 2;
                        if (row == null || row.length < rowBytes) row = new byte[rowBytes];
                        for (int r = 0; r < rh; r++) {
                            readFully(in, row, rowBytes);
                            synchronized (lock) {
                                if (frame != null && x >= 0 && y >= 0 && x + rw <= fbW && y + rh <= fbH) {
                                    frame.position(((y + r) * fbW + x) * 2);
                                    frame.put(row, 0, rowBytes);
                                }
                            }
                        }
                        synchronized (lock) {
                            if (bitmap != null && frame != null) {
                                frame.position(0);
                                bitmap.copyPixelsFromBuffer(frame);
                            }
                        }
                        postInvalidate();
                    } else if (enc == -223) {
                        resize(rw, rh);
                    } else {
                        throw new IOException("unexpected encoding " + enc);
                    }
                }
                incremental = 1;
                frames++;
                lastFrameAt = System.currentTimeMillis();
                sleepQuietly(20);
            } else if (type == 1) {
                in.read();
                u16s(in);
                int n = u16s(in);
                skipFully(in, n * 6L);
            } else if (type == 2) {
                // bell
            } else if (type == 3) {
                in.read();
                in.read();
                in.read();
                long n = readU32(in);
                skipFully(in, n);
            } else {
                throw new IOException("unexpected message " + type);
            }
        }
    }

    private void resize(int w, int h) {
        if (w <= 0 || h <= 0 || w > 4096 || h > 4096) return;
        synchronized (lock) {
            if (w == fbW && h == fbH) return;
            fbW = w;
            fbH = h;
            frame = ByteBuffer.allocate(w * h * 2);
            bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
        }
        status = "connected " + w + "x" + h;
        postInvalidate();
    }

    private void sendFramebufferRequest(OutputStream o, int incremental) throws IOException {
        synchronized (writeLock) {
            int w, h;
            synchronized (lock) {
                w = fbW;
                h = fbH;
            }
            o.write(new byte[]{3, (byte) incremental, 0, 0, 0, 0, (byte) (w >> 8), (byte) w, (byte) (h >> 8), (byte) h});
            o.flush();
        }
    }

    private void sendPointer(int mask, int x, int y) {
        OutputStream o = out;
        if (o == null) return;
        try {
            byte[] p = {5, (byte) mask, (byte) (x >> 8), (byte) x, (byte) (y >> 8), (byte) y};
            synchronized (writeLock) {
                o.write(p);
                o.flush();
            }
        } catch (IOException ignored) {
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (fbW <= 0) return true;
        float fx = (e.getX() - offX) / scale;
        float fy = (e.getY() - offY) / scale;
        int x = (int) Math.max(0, Math.min(fbW - 1, fx));
        int y = (int) Math.max(0, Math.min(fbH - 1, fy));
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
                sendPointer(1, x, y);
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                sendPointer(0, x, y);
                break;
            default:
                break;
        }
        return true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        Bitmap b;
        int w, h;
        synchronized (lock) {
            b = bitmap;
            w = fbW;
            h = fbH;
        }
        canvas.drawColor(Color.BLACK);
        if (b == null || w <= 0 || h <= 0) return;
        scale = Math.min(getWidth() / (float) w, getHeight() / (float) h);
        offX = (getWidth() - w * scale) / 2f;
        offY = (getHeight() - h * scale) / 2f;
        canvas.drawBitmap(b, null, new RectF(offX, offY, offX + w * scale, offY + h * scale), paint);
    }

    private void notifyDisconnected() {
        connected = false;
        ui.post(new Runnable() {
            @Override
            public void run() {
                listener.onVncDisconnected();
            }
        });
    }

    private static int u16s(InputStream in) throws IOException {
        int hi = in.read();
        int lo = in.read();
        if (hi < 0 || lo < 0) throw new EOFException();
        return (hi << 8) | lo;
    }

    private static int u16(byte[] b, int off) {
        return ((b[off] & 0xff) << 8) | (b[off + 1] & 0xff);
    }

    private static long readU32(InputStream in) throws IOException {
        long v = 0;
        for (int i = 0; i < 4; i++) {
            int c = in.read();
            if (c < 0) throw new EOFException();
            v = (v << 8) | c;
        }
        return v;
    }

    private static int readS32(InputStream in) throws IOException {
        return (int) readU32(in);
    }

    private static void writeAll(OutputStream o, byte[] b) throws IOException {
        o.write(b);
        o.flush();
    }

    private static void readFully(InputStream in, byte[] b, int len) throws IOException {
        int off = 0;
        while (off < len) {
            int n = in.read(b, off, len - off);
            if (n < 0) throw new EOFException();
            off += n;
        }
    }

    private static void skipFully(InputStream in, long len) throws IOException {
        while (len > 0) {
            long n = in.skip(len);
            if (n <= 0) {
                if (in.read() < 0) throw new EOFException();
                n = 1;
            }
            len -= n;
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    private static void closeQuietly(Socket s) {
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }
}
