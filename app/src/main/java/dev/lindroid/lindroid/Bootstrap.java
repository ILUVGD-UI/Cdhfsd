package dev.lindroid.lindroid;

import android.content.Context;
import android.os.Build;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * One-time setup:
 *  1. extract the ABI-matching proot binary from assets
 *  2. write a resolv.conf for the container
 *  3. download the Arch Linux ARM rootfs and extract it into filesDir/archlinux
 *  4. install the in-container session script
 */
public class Bootstrap {

    public interface Progress { void onProgress(String msg); }

    public static File rootfs(Context c) { return new File(c.getFilesDir(), "archlinux"); }

    public static boolean isSetup(Context c) {
        return new File(rootfs(c), "usr/local/bin/lindroid-start").exists();
    }

    public static synchronized void setup(Context c, Progress cb) throws Exception {
        String abi = Build.SUPPORTED_ABIS[0];
        String url;
        switch (abi) {
            case "arm64-v8a":
                url = "http://os.archlinuxarm.org/os/ArchLinuxARM-aarch64-latest.tar.gz";
                break;
            case "armeabi-v7a":
                url = "http://os.archlinuxarm.org/os/ArchLinuxARM-armv7-latest.tar.gz";
                break;
            default:
                throw new IllegalStateException(
                        "Unsupported ABI '" + abi + "'. Arch Linux ARM only exists for ARM devices.");
        }

        File files = c.getFilesDir();

        // 1. proot binary from APK assets (bin/proot-<abi>), put there at build time.
        File binDir = new File(files, "bin");
        binDir.mkdirs();
        File proot = new File(binDir, "proot");
        try (InputStream in = c.getAssets().open("bin/proot-" + abi);
             OutputStream out = new FileOutputStream(proot)) {
            copy(in, out);
        }
        proot.setExecutable(true, false);

        // 2. DNS config bind-mounted into the container.
        File etc = new File(files, "etc");
        etc.mkdirs();
        writeUtf8(new File(etc, "resolv.conf"),
                "nameserver 8.8.8.8\nnameserver 1.1.1.1\n");

        // 3. rootfs.
        File tar = new File(files, "rootfs.tar.gz");
        cb.onProgress("Downloading Arch Linux ARM rootfs…");
        download(url, tar, cb);
        cb.onProgress("Extracting rootfs (can take a few minutes)…");
        extract(tar, rootfs(c));
        tar.delete();

        // 4. session script inside the container.
        File script = new File(rootfs(c), "usr/local/bin/lindroid-start");
        script.getParentFile().mkdirs();
        try (InputStream in = c.getAssets().open("scripts/lindroid-start");
             OutputStream out = new FileOutputStream(script)) {
            copy(in, out);
        }
        script.setExecutable(true, false);

        cb.onProgress("Setup finished.");
    }

    private static void download(String url, File out, Progress cb) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.connect();
        long total = conn.getContentLengthLong();
        try (InputStream in = new BufferedInputStream(conn.getInputStream());
             OutputStream fos = new BufferedOutputStream(new FileOutputStream(out))) {
            byte[] buf = new byte[8192];
            long got = 0;
            int r;
            while ((r = in.read(buf)) > 0) {
                fos.write(buf, 0, r);
                got += r;
                if (total > 0) {
                    cb.onProgress(String.format("Downloading rootfs… %d%% (%d/%d MB)",
                            got * 100 / total, got / 1_048_576, total / 1_048_576));
                }
            }
        } finally {
            conn.disconnect();
        }
    }

    private static void extract(File tar, File dest) throws IOException {
        String destPath = dest.getCanonicalPath();
        dest.mkdirs();
        try (TarArchiveInputStream t = new TarArchiveInputStream(
                new GzipCompressorInputStream(new BufferedInputStream(new FileInputStream(tar))))) {
            TarArchiveEntry entry;
            while ((entry = t.getNextTarEntry()) != null) {
                String name = entry.getName();
                while (name.startsWith("./")) name = name.substring(2);
                if (name.isEmpty()) continue;

                File out = new File(dest, name);
                if (!out.getCanonicalPath().startsWith(destPath)) continue; // zip-slip guard

                if (entry.isDirectory()) {
                    out.mkdirs();
                    continue;
                }
                File parent = out.getParentFile();
                if (parent != null) parent.mkdirs();

                if (entry.isSymbolicLink()) {
                    Files.deleteIfExists(out.toPath());
                    Files.createSymbolicLink(out.toPath(), Paths.get(entry.getLinkName()));
                } else {
                    try (OutputStream o = new BufferedOutputStream(new FileOutputStream(out))) {
                        copy(t, o);
                    }
                    if (entry.isLink()) { // hardlink: best effort
                        try {
                            Files.deleteIfExists(out.toPath());
                            Files.createLink(out.toPath(), new File(dest, entry.getLinkName()).toPath());
                        } catch (Exception ignored) { }
                    }
                    int mode = entry.getMode();
                    out.setReadable((mode & 0400) != 0, false);
                    out.setWritable((mode & 0200) != 0, false);
                    out.setExecutable((mode & 0100) != 0, false);
                }
            }
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[8192];
        int r;
        while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
    }

    private static void writeUtf8(File f, String s) throws IOException {
        try (OutputStream o = new FileOutputStream(f)) { o.write(s.getBytes("UTF-8")); }
    }
}
