package dev.lindroid.lindroid;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Launches the proot binary (extracted to filesDir/bin/proot at setup time)
 * with the Arch Linux rootfs, binding Android paths the guest system needs.
 */
public class Proot {

    public static Process start(Context ctx) throws IOException {
        File files = ctx.getFilesDir();
        File rootfs = new File(files, "archlinux");
        File proot = new File(files, "bin/proot");
        if (!proot.exists()) {
            throw new IOException("proot binary missing — this APK was built without it. " +
                    "Build with scripts/build-proot.sh (see README).");
        }

        List<String> cmd = new ArrayList<>();
        cmd.add(proot.getAbsolutePath());
        cmd.addAll(Arrays.asList(
                "-0",                 // fake root user inside the container
                "-k",                 // --kill-on-exit: kill the whole session with proot
                "--link2symlink",     // Android filesystems don't support hardlinks
                "-r", rootfs.getAbsolutePath(),
                "-b", "/dev",
                "-b", "/proc",
                "-b", "/sys",
                "-b", new File(files, "etc/resolv.conf").getAbsolutePath() + ":/etc/resolv.conf",
                "-b", "/storage/emulated/0:/sdcard",   // shared storage, visible as /sdcard
                "-w", "/root",
                "/bin/bash", "/usr/local/bin/lindroid-start"
        ));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        Map<String, String> env = pb.environment();
        env.put("HOME", "/root");
        env.put("USER", "root");
        env.put("TERM", "xterm-256color");
        env.put("LANG", "C.UTF-8");
        env.put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        pb.redirectErrorStream(true);
        pb.directory(files);

        Process p = pb.start();
        // Drain stdout/stderr so the pipe never fills up and blocks proot.
        new Thread(() -> {
            try {
                InputStream in = p.getInputStream();
                byte[] buf = new byte[4096];
                while (in.read(buf) >= 0) { /* discard */ }
            } catch (IOException ignored) { }
        }).start();
        return p;
    }
}
