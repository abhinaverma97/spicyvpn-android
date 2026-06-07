package app.spicypepper.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import androidx.core.app.NotificationCompat;

public class VPNService extends VpnService {

    private static final int NOTIFICATION_ID = 1;
    private static final String CHANNEL_ID = "spicyvpn_channel";
    public static final String ACTION_DISCONNECT = "app.spicypepper.android.DISCONNECT";

    private static VPNService instance;
    private ParcelFileDescriptor vpnInterface;

    public static VPNService getInstance() {
        return instance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        createNotificationChannel();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, buildNotification("VPN is active"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, buildNotification("VPN is active"));
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_DISCONNECT.equals(intent.getAction())) {
            BoxManager.getInstance().stop();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        instance = null;
        if (vpnInterface != null) {
            try {
                vpnInterface.close();
            } catch (Exception ignored) {
            }
            vpnInterface = null;
        }
        super.onDestroy();
    }

    @Override
    public void onRevoke() {
        BoxManager.getInstance().onVpnRevoked();
        stopSelf();
    }

    public int openTun(String dnsStr, int mtu) {
        String[] dnsServers = dnsStr.split(",");

        Builder builder = new Builder()
                .setSession(getString(R.string.app_name))
                .addAddress("172.19.0.1", 30)
                .addRoute("0.0.0.0", 0);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false);
        }

        for (String dns : dnsServers) {
            dns = dns.trim();
            if (!dns.isEmpty()) {
                builder.addDnsServer(dns);
            }
        }

        if (mtu > 0) {
            builder.setMtu(mtu);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setBlocking(true);
        }

        try {
            ParcelFileDescriptor old = vpnInterface;
            vpnInterface = builder.establish();
            if (old != null) {
                old.close();
            }
            if (vpnInterface != null) {
                return vpnInterface.getFd();
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    public void closeTun() {
        if (vpnInterface != null) {
            try {
                vpnInterface.close();
            } catch (Exception ignored) {
            }
            vpnInterface = null;
        }
        stopSelf();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "SpicyVPN",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("VPN connection status");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification(String text) {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Intent disconnectIntent = new Intent(this, VPNService.class);
        disconnectIntent.setAction(ACTION_DISCONNECT);
        PendingIntent disconnectPendingIntent = PendingIntent.getService(
                this, 1, disconnectIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("SpicyVPN")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentIntent(pendingIntent)
                .addAction(0, "Disconnect", disconnectPendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    public void updateNotification(String text) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification(text));
        }
    }
}
