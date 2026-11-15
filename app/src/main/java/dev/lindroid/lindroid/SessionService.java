package dev.lindroid.lindroid;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Foreground service that keeps the whole session alive:
 *  - proot + Arch Linux rootfs running TigerVNC on :1 (port 5901)
 *  - a WebSocket->TCP bridge on 6080 so noVNC can talk to VNC
 *  - a tiny HTTP server on 8765 that serves the noVNC client
 */
public class SessionService extends Service {

    public static final String ACTION_START = "dev.lindroid.action.START";
    public static final String ACTION_STOP  = "dev.lindroid.action.STOP";
    private static final String CHANNEL_ID = "lindroid_session";
    private static final int NOTIF_ID = 1;

    private static volatile boolean running = false;
    public static boolean isRunning() { return running; }

    private Process proot;
    private WebSocketProxy vncBridge;
    private AssetServer httpServer;

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID, "Arch Linux session", NotificationManager.IMPORTANCE_LOW));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSession();
            return START_NOT_STICKY;
        }
        if (running) return START_NOT_STICKY;
        running = true;
        goForeground();
        new Thread(this::runSession).start();
        return START_NOT_STICKY;
    }

    private void goForeground() {
        Notification n = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("Lindroid")
                .setContentText("Arch Linux session is running")
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private void runSession() {
        try {
            if (!Bootstrap.isSetup(this)) {
                throw new IllegalStateException("Run SETUP first");
            }
            File novnc = new File(getFilesDir(), "novnc");
            if (!new File(novnc, "vnc.html").exists()) {
                copyAssetDir("novnc", novnc);
            }
            httpServer = new AssetServer(8765, novnc);
            httpServer.start();
            vncBridge = new WebSocketProxy(6080);
            vncBridge.start();
            proot = Proot.start(this);
            proot.waitFor(); // session lives as long as proot does
        } catch (Exception e) {
            // Surface the error in the notification and shut down cleanly.
            Notification n = new Notification.Builder(this, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentTitle("Lindroid session failed")
                    .setContentText(String.valueOf(e.getMessage()))
                    .build();
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.notify(NOTIF_ID + 1, n);
        } finally {
            stopSession();
        }
    }

    private synchronized void stopSession() {
        running = false;
        if (proot != null) proot.destroy();
        if (vncBridge != null) vncBridge.close();
        if (httpServer != null) httpServer.close();
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        stopSession();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void copyAssetDir(String assetPath, File outDir) throws IOException {
        outDir.mkdirs();
        String[] children = getAssets().list(assetPath);
        if (children == null) return;
        for (String child : children) {
            String src = assetPath + "/" + child;
            File dst = new File(outDir, child);
            if (getAssets().list(src).length == 0) {
                try (InputStream in = getAssets().open(src);
                     FileOutputStream fos = new FileOutputStream(dst)) {
                    byte[] buf = new byte[8192];
                    int r;
                    while ((r = in.read(buf)) > 0) fos.write(buf, 0, r);
                }
            } else {
                copyAssetDir(src, dst);
            }
        }
    }
}
