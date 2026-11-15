package dev.lindroid.lindroid;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * Minimal RFC 6455 WebSocket server that bridges noVNC (WebView) to the
 * TigerVNC server running inside the container on 127.0.0.1:5901.
 *
 * noVNC speaks WebSocket; VNC speaks raw TCP. This class translates between
 * the two so no websockify/python is needed inside the container.
 */
public class WebSocketProxy extends Thread {

    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final String VNC_HOST = "127.0.0.1";
    private static final int VNC_PORT = 5901;

    private final ServerSocket server;
    private volatile boolean open = true;

    public WebSocketProxy(int port) throws IOException {
        server = new ServerSocket(port);
    }

    public void close() {
        open = false;
        try { server.close(); } catch (IOException ignored) { }
    }

    @Override
    public void run() {
        while (open) {
            try {
                Socket ws = server.accept();
                new Thread(() -> handle(ws)).start();
            } catch (IOException e) {
                if (open) e.printStackTrace();
            }
        }
    }

    private void handle(Socket ws) {
        Socket vnc = null;
        try {
            vnc = waitForVnc(3600_000); // first boot installs packages; VNC may be slow to appear
            if (vnc == null) { ws.close(); return; }
            handshake(ws);

            final Socket v = vnc;
            Thread up = new Thread(() -> pumpWsToVnc(ws, v));
            Thread down = new Thread(() -> pumpVncToWs(ws, v));
            up.start();
            down.start();
            up.join();
        } catch (Exception ignored) {
        } finally {
            try { ws.close(); } catch (IOException ignored) { }
            if (vnc != null) try { vnc.close(); } catch (IOException ignored) { }
        }
    }

    /** Poll until the in-container VNC server is accepting connections. */
    private Socket waitForVnc(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                return new Socket(VNC_HOST, VNC_PORT);
            } catch (IOException e) {
                try { Thread.sleep(2000); } catch (InterruptedException ignored) { }
            }
        }
        return null;
    }

    private void handshake(Socket ws) throws Exception {
        InputStream in = new BufferedInputStream(ws.getInputStream());
        StringBuilder headers = new StringBuilder();
        int b;
        while (true) {
            b = in.read();
            if (b < 0) throw new EOFException();
            headers.append((char) b);
            if (headers.toString().endsWith("\r\n\r\n")) break;
        }
        String req = headers.toString();
        String key = null;
        String protocol = null;
        for (String line : req.split("\r\n")) {
            if (line.toLowerCase().startsWith("sec-websocket-key:"))
                key = line.substring(line.indexOf(':') + 1).trim();
            if (line.toLowerCase().startsWith("sec-websocket-protocol:"))
                protocol = line.substring(line.indexOf(':') + 1).trim();
        }
        if (key == null) throw new IOException("not a websocket request");

        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        String accept = Base64.getEncoder().encodeToString(
                sha1.digest((key + WS_GUID).getBytes(StandardCharsets.ISO_8859_1)));

        StringBuilder resp = new StringBuilder();
        resp.append("HTTP/1.1 101 Switching Protocols\r\n");
        resp.append("Upgrade: websocket\r\n");
        resp.append("Connection: Upgrade\r\n");
        resp.append("Sec-WebSocket-Accept: ").append(accept).append("\r\n");
        // noVNC requests the "binary" sub-protocol; we must echo it.
        if (protocol != null && protocol.contains("binary"))
            resp.append("Sec-WebSocket-Protocol: binary\r\n");
        resp.append("\r\n");

        OutputStream out = ws.getOutputStream();
        out.write(resp.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    /** WebSocket frames -> raw VNC bytes (binary opcodes only). */
    private void pumpWsToVnc(Socket ws, Socket vnc) {
        try {
            InputStream in = ws.getInputStream();
            OutputStream out = vnc.getOutputStream();
            while (true) {
                int b1 = in.read();
                if (b1 < 0) return;
                int b2 = in.read();
                if (b2 < 0) return;
                int opcode = b1 & 0x0F;
                long len = b2 & 0x7F;
                if (len == 126) len = readShort(in);
                else if (len == 127) len = readLong(in);

                byte[] mask = null;
                if ((b2 & 0x80) != 0) { // client->server frames are masked
                    mask = new byte[4];
                    readFully(in, mask, 4);
                }
                byte[] payload = new byte[(int) len];
                readFully(in, payload, (int) len);
                if (mask != null)
                    for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];

                switch (opcode) {
                    case 0x0: // continuation: VNC is a stream, forwarding in order is safe
                    case 0x2: // binary
                        out.write(payload);
                        out.flush();
                        break;
                    case 0x8: // close
                        return;
                    case 0x9: // ping -> pong
                        ws.getOutputStream().write(pong(payload));
                        ws.getOutputStream().flush();
                        break;
                    default:  // 0x1 text, 0xA pong: ignore
                }
            }
        } catch (IOException e) {
            // connection done
        }
    }

    /** Raw VNC bytes -> unmasked binary WebSocket frames. */
    private void pumpVncToWs(Socket ws, Socket vnc) {
        try {
            InputStream in = vnc.getInputStream();
            OutputStream out = ws.getOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(frame(buf, n));
                out.flush();
            }
        } catch (IOException e) {
            // connection done
        }
    }

    private static byte[] pong(byte[] payload) {
        byte[] f = new byte[2 + payload.length];
        f[0] = (byte) 0x8A;
        f[1] = (byte) payload.length;
        System.arraycopy(payload, 0, f, 2, payload.length);
        return f;
    }

    private static byte[] frame(byte[] payload, int n) {
        if (n < 126) {
            byte[] f = new byte[2 + n];
            f[0] = (byte) 0x82; f[1] = (byte) n;
            System.arraycopy(payload, 0, f, 2, n);
            return f;
        } else if (n <= 0xFFFF) {
            byte[] f = new byte[4 + n];
            f[0] = (byte) 0x82; f[1] = (byte) 126;
            f[2] = (byte) (n >> 8); f[3] = (byte) n;
            System.arraycopy(payload, 0, f, 4, n);
            return f;
        } else {
            byte[] f = new byte[10 + n];
            f[0] = (byte) 0x82; f[1] = (byte) 127;
            long m = n;
            for (int i = 9; i >= 2; i--) { f[i] = (byte) (m & 0xFF); m >>= 8; }
            System.arraycopy(payload, 0, f, 10, n);
            return f;
        }
    }

    private static void readFully(InputStream in, byte[] buf, int len) throws IOException {
        int off = 0;
        while (off < len) {
            int r = in.read(buf, off, len - off);
            if (r < 0) throw new EOFException();
            off += r;
        }
    }

    private static long readShort(InputStream in) throws IOException {
        return ((long) in.read() << 8) | in.read();
    }

    private static long readLong(InputStream in) throws IOException {
        long v = 0;
        for (int i = 0; i < 8; i++) v = (v << 8) | in.read();
        return v;
    }
}
