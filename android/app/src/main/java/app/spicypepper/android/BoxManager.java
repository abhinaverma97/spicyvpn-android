package app.spicypepper.android;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import io.nekohasekai.libbox.CommandClient;
import io.nekohasekai.libbox.CommandClientHandler;
import io.nekohasekai.libbox.CommandClientOptions;
import io.nekohasekai.libbox.CommandServer;
import io.nekohasekai.libbox.CommandServerHandler;
import io.nekohasekai.libbox.ConnectionEvents;
import io.nekohasekai.libbox.Libbox;
import io.nekohasekai.libbox.LogEntry;
import io.nekohasekai.libbox.LogIterator;
import io.nekohasekai.libbox.OverrideOptions;
import io.nekohasekai.libbox.OutboundGroupItemIterator;
import io.nekohasekai.libbox.OutboundGroupIterator;
import io.nekohasekai.libbox.SetupOptions;
import io.nekohasekai.libbox.StatusMessage;
import io.nekohasekai.libbox.StringIterator;
import io.nekohasekai.libbox.SystemProxyStatus;

public class BoxManager {

    public interface EventListener {
        void onStatusChanged(String status);
        void onLog(String message);
    }

    private static final String TAG = "BoxManager";
    private static final BoxManager INSTANCE = new BoxManager();

    private CommandServer server;
    private CommandClient client;
    private String status = "disconnected";
    private String subLink = "";
    private final List<String> logs = new ArrayList<>();
    private Context appContext;
    private boolean initialized;
    private EventListener listener;

    public static BoxManager getInstance() {
        return INSTANCE;
    }

    public void setListener(EventListener listener) {
        this.listener = listener;
    }

    public String getSubLink() {
        return subLink;
    }

    public void setSubLink(String link) {
        this.subLink = link;
    }

    public synchronized String getStatus() {
        return status;
    }

    public synchronized String getLogs() {
        synchronized (logs) {
            StringBuilder sb = new StringBuilder();
            for (String line : logs) {
                sb.append(line).append("\n");
            }
            return sb.toString();
        }
    }

    public synchronized void clearLogs() {
        synchronized (logs) {
            logs.clear();
        }
    }

    public void onVpnRevoked() {
        setStatus("disconnected");
        emitStatus("disconnected");
    }

    public synchronized void start(Context context, String url) throws Exception {
        this.appContext = context.getApplicationContext();

        if (!initialized) {
            initLibbox(context);
        }

        stop();

        clearLogs();

        String uri = url.startsWith("http")
                ? SubscriptionManager.resolveUri(url)
                : url;

        String configJson = ConfigGenerator.generate(uri);

        File configDir = new File(context.getFilesDir(), "config");
        configDir.mkdirs();
        File configFile = new File(configDir, "sing-box.json");
        Files.write(configFile.toPath(), configJson.getBytes(StandardCharsets.UTF_8));

        setStatus("connecting");
        emitStatus("connecting");

        PlatformInterfaceImpl platformImpl = new PlatformInterfaceImpl(context);

        server = Libbox.newCommandServer(new CommandServerHandler() {
            @Override public void serviceReload() {
                Log.d(TAG, "Service reload requested");
            }
            @Override public void serviceStop() {
                Log.d(TAG, "Service stopped");
                BoxManager.this.setStatus("disconnected");
                BoxManager.this.emitStatus("disconnected");
            }
            @Override public void setSystemProxyEnabled(boolean enabled) {}
            @Override public SystemProxyStatus getSystemProxyStatus() { return null; }
            @Override public void writeDebugMessage(String message) { Log.d(TAG, "Debug: " + message); }
            @Override public void triggerNativeCrash() {}
        }, platformImpl);

        server.start();

        OverrideOptions overrideOptions = new OverrideOptions();
        server.startOrReloadService(configJson, overrideOptions);

        CommandClientOptions clientOptions = new CommandClientOptions();
        clientOptions.setStatusInterval(2000);
        clientOptions.addCommand(Libbox.CommandStatus);
        clientOptions.addCommand(Libbox.CommandLog);

        client = Libbox.newCommandClient(new CommandClientHandler() {
            @Override public void connected() {
                Log.d(TAG, "CommandClient connected");
            }
            @Override public void disconnected(String s) {
                Log.d(TAG, "CommandClient disconnected: " + s);
                BoxManager.this.setStatus("disconnected");
                BoxManager.this.emitStatus("disconnected");
            }
            @Override
            public void writeStatus(StatusMessage message) {
                if ("connected".equals(BoxManager.this.status)) return;
                if ("disconnected".equals(BoxManager.this.status)) return;
                BoxManager.this.setStatus("connected");
                BoxManager.this.emitStatus("connected");
                BoxManager.this.updateNotification("VPN Connected");
            }
            @Override
            public void writeLogs(LogIterator iterator) {
                while (iterator.hasNext()) {
                    LogEntry entry = iterator.next();
                    String msg = entry.getMessage();
                    synchronized (BoxManager.this.logs) {
                        BoxManager.this.logs.add(msg);
                        if (BoxManager.this.logs.size() > 1000) {
                            BoxManager.this.logs.remove(0);
                        }
                    }
                    emitLog(msg);
                }
            }
            @Override public void writeGroups(OutboundGroupIterator it) {}
            @Override public void writeOutbounds(OutboundGroupItemIterator it) {}
            @Override public void initializeClashMode(StringIterator it, String s) {}
            @Override public void updateClashMode(String s) {}
            @Override public void writeConnectionEvents(ConnectionEvents ce) {}
            @Override public void setDefaultLogLevel(int i) {}
            @Override public void clearLogs() {}
        }, clientOptions);

        client.connect();
    }

    private void initLibbox(Context context) throws Exception {
        SetupOptions options = new SetupOptions();
        options.setBasePath(context.getFilesDir().getAbsolutePath());
        options.setWorkingPath(context.getCacheDir().getAbsolutePath());
        options.setTempPath(context.getCacheDir().getAbsolutePath());
        options.setDebug(false);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            options.setFixAndroidStack(true);
        }
        Libbox.setup(options);
        initialized = true;
    }

    public synchronized void stop() {
        setStatus("disconnected");
        emitStatus("disconnected");

        VPNService vpn = VPNService.getInstance();
        if (vpn != null) {
            vpn.closeTun();
        } else if (appContext != null) {
            Intent intent = new Intent(appContext, VPNService.class);
            appContext.stopService(intent);
        }

        final CommandClient finalClient = client;
        final CommandServer finalServer = server;
        client = null;
        server = null;

        new Thread(new Runnable() {
            @Override
            public void run() {
                if (finalClient != null) {
                    try { finalClient.disconnect(); } catch (Exception ignored) {}
                    try { finalClient.serviceClose(); } catch (Exception ignored) {}
                }
                if (finalServer != null) {
                    try { finalServer.closeService(); } catch (Exception ignored) {}
                    try { finalServer.close(); } catch (Exception ignored) {}
                }
            }
        }).start();
    }

    private synchronized void setStatus(String newStatus) {
        this.status = newStatus;
    }

    private void emitStatus(String status) {
        EventListener l = listener;
        if (l != null) l.onStatusChanged(status);
    }

    private void emitLog(String message) {
        EventListener l = listener;
        if (l != null) l.onLog(message);
    }

    private void updateNotification(String text) {
        VPNService vpn = VPNService.getInstance();
        if (vpn != null) vpn.updateNotification(text);
    }
}
