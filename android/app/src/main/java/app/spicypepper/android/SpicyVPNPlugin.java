package app.spicypepper.android;

import android.content.Intent;
import android.net.VpnService;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

@CapacitorPlugin(name = "SpicyVPN")
public class SpicyVPNPlugin extends Plugin {

    private static final int VPN_PREPARE_REQUEST = 1000;

    @Override
    public void load() {
        super.load();
        BoxManager.getInstance().setListener(new BoxManager.EventListener() {
            @Override
            public void onStatusChanged(String status) {
                JSObject data = new JSObject();
                data.put("status", status);
                notifyListeners("vpnStatusChanged", data);
            }

            @Override
            public void onLog(String message) {
                JSObject data = new JSObject();
                data.put("message", message);
                notifyListeners("vpnLog", data);
            }
        });
    }

    @PluginMethod
    public void startVPN(PluginCall call) {
        String url = call.getString("url");
        if (url == null || url.isEmpty()) {
            call.reject("URL is required");
            return;
        }

        Intent intent = VpnService.prepare(getContext());
        if (intent != null) {
            saveCall(call);
            startActivityForResult(call, intent, VPN_PREPARE_REQUEST);
            return;
        }

        doStartVPN(call, url);
    }

    private void doStartVPN(PluginCall call, String url) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    BoxManager.getInstance().start(getContext(), url);
                    JSObject ret = new JSObject();
                    ret.put("success", true);
                    call.resolve(ret);
                } catch (Exception e) {
                    call.reject("Failed to start VPN: " + e.getMessage());
                }
            }
        }).start();
    }

    @Override
    protected void handleOnActivityResult(int requestCode, int resultCode, Intent data) {
        super.handleOnActivityResult(requestCode, resultCode, data);
        if (requestCode == VPN_PREPARE_REQUEST) {
            PluginCall savedCall = getSavedCall();
            if (savedCall != null) {
                if (resultCode == android.app.Activity.RESULT_OK) {
                    doStartVPN(savedCall, savedCall.getString("url"));
                } else {
                    savedCall.reject("VPN permission denied");
                }
            }
        }
    }

    @PluginMethod
    public void stopVPN(PluginCall call) {
        try {
            BoxManager.getInstance().stop();
            call.resolve();
        } catch (Exception e) {
            call.reject("Failed to stop VPN: " + e.getMessage());
        }
    }

    @PluginMethod
    public void getStatus(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("status", BoxManager.getInstance().getStatus());
        call.resolve(ret);
    }

    @PluginMethod
    public void getLogs(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("logs", BoxManager.getInstance().getLogs());
        call.resolve(ret);
    }

    @PluginMethod
    public void fetchStats(PluginCall call) {
        String url = call.getString("url");
        if (url == null || url.isEmpty()) {
            call.reject("URL is required");
            return;
        }
        try {
            JSObject stats = SubscriptionManager.fetch(url);
            call.resolve(stats);
        } catch (Exception e) {
            call.reject(e.getMessage());
        }
    }

    @PluginMethod
    public void getSubLink(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("link", BoxManager.getInstance().getSubLink());
        call.resolve(ret);
    }

    @PluginMethod
    public void setSubLink(PluginCall call) {
        String link = call.getString("link");
        BoxManager.getInstance().setSubLink(link != null ? link : "");
        call.resolve();
    }
}
