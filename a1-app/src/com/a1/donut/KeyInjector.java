package com.a1.donut;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;

/* QEMU exposes the guest's serial console (a root shell from Donut's init) on
 * 127.0.0.1:5555. Android's own input command is the only way to deliver
 * BACK, which has no VNC keysym, so the on-screen buttons use this channel. */
public final class KeyInjector {

    private static final Object LOCK = new Object();

    private KeyInjector() {
    }

    public static void send(String line) {
        synchronized (LOCK) {
            Socket socket = null;
            try {
                socket = new Socket();
                for (int attempt = 0; attempt < 3; attempt++) {
                    try {
                        socket.connect(new InetSocketAddress("127.0.0.1", 5555), 700);
                        break;
                    } catch (IOException e) {
                        if (attempt == 2) throw e;
                        socket = new Socket();
                        try {
                            Thread.sleep(250);
                        } catch (InterruptedException ignored) {
                            return;
                        }
                    }
                }
                socket.setSoTimeout(120);
                OutputStream out = socket.getOutputStream();
                out.write((line + "\n").getBytes("US-ASCII"));
                out.flush();

                InputStream in = socket.getInputStream();
                byte[] buf = new byte[4096];
                long end = System.currentTimeMillis() + 700;
                while (System.currentTimeMillis() < end) {
                    try {
                        if (in.read(buf) < 0) break;
                    } catch (SocketTimeoutException ignored) {
                    }
                }
            } catch (IOException ignored) {
            } finally {
                if (socket != null) {
                    try {
                        socket.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        }
    }
}
