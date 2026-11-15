package dev.lindroid.lindroid;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Map;

/**
 * Tiny static file server used to serve the noVNC client on
 * http://127.0.0.1:8765 — serving over http (instead of file://) lets the
 * WebView open the ws:// VNC connection without mixed-content problems.
 */
public class AssetServer extends Thread {

    private static final Map<String, String> MIME = new HashMap<>();
    static {
        MIME.put("html", "text/html");
        MIME.put("js", "text/javascript");
        MIME.put("mjs", "text/javascript");
        MIME.put("css", "text/css");
        MIME.put("png", "image/png");
        MIME.put("jpg", "image/jpeg");
        MIME.put("svg", "image/svg+xml");
        MIME.put("json", "application/json");
        MIME.put("woff", "font/woff");
        MIME.put("woff2", "font/woff2");
        MIME.put("ico", "image/x-icon");
    }

    private final ServerSocket server;
    private final File root;
    private volatile boolean open = true;

    public AssetServer(int port, File root) throws IOException {
        this.server = new ServerSocket(port);
        this.root = root;
    }

    public void close() {
        open = false;
        try { server.close(); } catch (IOException ignored) { }
    }

    @Override
    public void run() {
        while (open) {
            try {
                Socket s = server.accept();
                new Thread(() -> serve(s)).start();
            } catch (IOException e) {
                if (open) e.printStackTrace();
            }
        }
    }

    private void serve(Socket s) {
        try (Socket sock = s) {
            InputStream in = sock.getInputStream();
            StringBuilder head = new StringBuilder();
            int b;
            while (true) {
                b = in.read();
                if (b < 0) return;
                head.append((char) b);
                if (head.toString().endsWith("\r\n\r\n")) break;
            }
            String line = head.toString().split("\r\n")[0];
            String[] parts = line.split(" ");
            if (parts.length < 2 || !parts[0].equals("GET")) { send(sock, 400, "text/plain", "Bad request".getBytes()); return; }

            String path = URLDecoder.decode(parts[1], "UTF-8");
            if (path.equals("/")) path = "/vnc.html";
            String clean = path.replace("\\", "/");
            if (clean.contains("..")) { send(sock, 403, "text/plain", "Forbidden".getBytes()); return; }

            File f = new File(root, clean);
            if (!f.isFile()) { send(sock, 404, "text/plain", "Not found".getBytes()); return; }

            String ext = f.getName().contains(".")
                    ? f.getName().substring(f.getName().lastIndexOf('.') + 1).toLowerCase()
                    : "";
            byte[] body;
            try (InputStream fis = new FileInputStream(f)) {
                body = new byte[(int) f.length()];
                int off = 0;
                while (off < body.length) {
                    int r = fis.read(body, off, body.length - off);
                    if (r < 0) break;
                    off += r;
                }
            }
            send(sock, 200, MIME.getOrDefault(ext, "application/octet-stream"), body);
        } catch (IOException ignored) { }
    }

    private void send(Socket s, int code, String type, byte[] body) throws IOException {
        String status = code == 200 ? "200 OK" : String.valueOf(code);
        OutputStream out = new BufferedOutputStream(s.getOutputStream());
        String header = "HTTP/1.1 " + status + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-cache\r\n"
                + "Connection: close\r\n\r\n";
        out.write(header.getBytes("ISO-8859-1"));
        out.write(body);
        out.flush();
    }
}
