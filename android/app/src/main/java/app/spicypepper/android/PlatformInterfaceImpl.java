package app.spicypepper.android;

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

import io.nekohasekai.libbox.ConnectionOwner;
import io.nekohasekai.libbox.InterfaceUpdateListener;
import io.nekohasekai.libbox.Libbox;
import io.nekohasekai.libbox.LocalDNSTransport;
import io.nekohasekai.libbox.NeighborUpdateListener;
import io.nekohasekai.libbox.NetworkInterface;
import io.nekohasekai.libbox.NetworkInterfaceIterator;
import io.nekohasekai.libbox.Notification;
import io.nekohasekai.libbox.PlatformInterface;
import io.nekohasekai.libbox.StringIterator;
import io.nekohasekai.libbox.TunOptions;
import io.nekohasekai.libbox.WIFIState;

public class PlatformInterfaceImpl implements PlatformInterface {

    private static final String TAG = "PlatformInterface";
    private final Context context;

    public PlatformInterfaceImpl(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public int openTun(TunOptions options) {
        try {
            Intent intent = new Intent(context, VPNService.class);
            context.startForegroundService(intent);

            StringBuilder dnsBuilder = new StringBuilder();
            try {
                StringIterator dnsServers = options.getDNSServerAddress();
                if (dnsServers != null) {
                    while (dnsServers.hasNext()) {
                        if (dnsBuilder.length() > 0) dnsBuilder.append(",");
                        dnsBuilder.append(dnsServers.next());
                    }
                }
            } catch (Exception ignored) {}

            if (dnsBuilder.length() == 0) {
                dnsBuilder.append("1.1.1.1,8.8.8.8");
            }

            // Wait up to 3 seconds for VPNService to be ready (it starts asynchronously)
            VPNService vpnService = null;
            for (int i = 0; i < 30; i++) {
                vpnService = VPNService.getInstance();
                if (vpnService != null) break;
                Thread.sleep(100);
            }

            if (vpnService != null) {
                return vpnService.openTun(dnsBuilder.toString(), options.getMTU());
            } else {
                Log.e(TAG, "openTun: VPNService did not start in time");
            }
        } catch (Exception e) {
            Log.e(TAG, "openTun failed", e);
        }
        return -1;
    }

    @Override
    public void autoDetectInterfaceControl(int fd) {
        // Protect this socket from being captured by the VPN tunnel.
        // Without this, direct outbound sockets loop back into the TUN and fail.
        VPNService vpnService = VPNService.getInstance();
        if (vpnService != null) {
            vpnService.protect(fd);
        }
    }

    @Override
    public void clearDNSCache() {}

    @Override
    public void closeDefaultInterfaceMonitor(InterfaceUpdateListener listener) {}

    @Override
    public void closeNeighborMonitor(NeighborUpdateListener listener) {}

    @Override
    public ConnectionOwner findConnectionOwner(int i, String s, int i1, String s1, int i2) {
        // Must not return null - sing-box 1.14 dereferences this and will SIGSEGV on null.
        // Return a stub with userId=-1 (unknown process) to safely skip process-based routing.
        ConnectionOwner owner = new ConnectionOwner();
        owner.setUserId(-1);
        return owner;
    }

    private int resolveInterfaceIndex(String name) {
        if (name == null || name.isEmpty()) return 0;
        try {
            return android.system.Os.if_nametoindex(name);
        } catch (Exception e) {
            Log.e(TAG, "resolveInterfaceIndex failed for " + name, e);
        }
        return 0;
    }

    @Override
    public NetworkInterfaceIterator getInterfaces() {
        List<NetworkInterface> list = new ArrayList<>();
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                // Iterate ALL networks and pick the physical one (skip VPN transport).
                // When VPN is active, getActiveNetwork() returns the VPN itself, not WiFi.
                for (Network network : cm.getAllNetworks()) {
                    NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                    if (caps == null) continue;
                    // Skip the VPN network
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
                    // Only include networks with internet
                    if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;

                    LinkProperties lp = cm.getLinkProperties(network);
                    String ifaceName = (lp != null) ? lp.getInterfaceName() : null;
                    if (ifaceName == null) continue;

                    NetworkInterface iface = new NetworkInterface();
                    int type = Libbox.InterfaceTypeOther;
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        type = Libbox.InterfaceTypeWIFI;
                    } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                        type = Libbox.InterfaceTypeCellular;
                    } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                        type = Libbox.InterfaceTypeEthernet;
                    }
                    iface.setType(type);
                    iface.setName(ifaceName);
                    iface.setIndex(resolveInterfaceIndex(ifaceName));
                    
                    int dumpFlags = 0;
                    if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                        dumpFlags = android.system.OsConstants.IFF_UP | android.system.OsConstants.IFF_RUNNING;
                    }
                    try {
                        java.net.NetworkInterface jni = java.net.NetworkInterface.getByName(ifaceName);
                        if (jni != null) {
                            if (jni.isLoopback()) dumpFlags |= android.system.OsConstants.IFF_LOOPBACK;
                            if (jni.isPointToPoint()) dumpFlags |= android.system.OsConstants.IFF_POINTOPOINT;
                            if (jni.supportsMulticast()) dumpFlags |= android.system.OsConstants.IFF_MULTICAST;
                        }
                    } catch (Exception ignored) {}
                    iface.setFlags(dumpFlags);
                    
                    boolean metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
                    iface.setMetered(metered);

                    List<String> addresses = new ArrayList<>();
                    // 1. Try LinkProperties addresses first
                    if (lp != null) {
                        for (android.net.LinkAddress linkAddr : lp.getLinkAddresses()) {
                            java.net.InetAddress addr = linkAddr.getAddress();
                            if (addr instanceof java.net.Inet4Address) {
                                addresses.add(addr.getHostAddress() + "/" + linkAddr.getPrefixLength());
                            }
                        }
                    }
                    // 2. Fallback to NetworkInterface
                    if (addresses.isEmpty()) {
                        try {
                            java.net.NetworkInterface jni = java.net.NetworkInterface.getByName(ifaceName);
                            if (jni != null) {
                                for (java.net.InterfaceAddress addr : jni.getInterfaceAddresses()) {
                                    if (addr.getAddress() instanceof java.net.Inet4Address) {
                                        addresses.add(addr.getAddress().getHostAddress() + "/" + addr.getNetworkPrefixLength());
                                    }
                                }
                            }
                        } catch (Exception ignored) {}
                    }
                    // 3. Fallback to NetworkInterface enumeration lookup
                    if (addresses.isEmpty()) {
                        try {
                            java.util.Enumeration<java.net.NetworkInterface> interfaces = java.net.NetworkInterface.getNetworkInterfaces();
                            if (interfaces != null) {
                                while (interfaces.hasMoreElements()) {
                                    java.net.NetworkInterface jni = interfaces.nextElement();
                                    if (ifaceName.equals(jni.getName())) {
                                        for (java.net.InterfaceAddress addr : jni.getInterfaceAddresses()) {
                                            if (addr.getAddress() instanceof java.net.Inet4Address) {
                                                addresses.add(addr.getAddress().getHostAddress() + "/" + addr.getNetworkPrefixLength());
                                            }
                                        }
                                        break;
                                    }
                                }
                            }
                        } catch (Exception ignored) {}
                    }

                    if (!addresses.isEmpty()) {
                        final String[] addrArray = addresses.toArray(new String[0]);
                        iface.setAddresses(new StringIterator() {
                            int index = 0;
                            @Override public boolean hasNext() { return index < addrArray.length; }
                            @Override public String next() { return addrArray[index++]; }
                            @Override public int len() { return addrArray.length; }
                        });
                    }
                    
                    list.add(iface);
                }
            }
        } catch (Exception ignored) {}

        NetworkInterface[] array = list.toArray(new NetworkInterface[0]);
        return new NetworkInterfaceIterator() {
            int index = 0;
            @Override public boolean hasNext() { return index < array.length; }
            @Override public NetworkInterface next() { return array[index++]; }
        };
    }

    @Override
    public boolean includeAllNetworks() { return true; }

    @Override
    public LocalDNSTransport localDNSTransport() { return null; }

    @Override
    public WIFIState readWIFIState() { return null; }

    @Override
    public void registerMyInterface(String s) {}

    @Override
    public void sendNotification(Notification notification) {
        try {
            VPNService vpnService = VPNService.getInstance();
            if (vpnService != null) {
                String text = notification.getTitle();
                if (notification.getBody() != null && !notification.getBody().isEmpty()) {
                    text += ": " + notification.getBody();
                }
                vpnService.updateNotification(text);
            }
        } catch (Exception ignored) {}
    }

    @Override
    public void startDefaultInterfaceMonitor(InterfaceUpdateListener listener) {
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                NetworkRequest request = new NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                        .build();

                cm.registerNetworkCallback(request, new ConnectivityManager.NetworkCallback() {
                    private void notifyListener() {
                        try {
                            Network physicalNetwork = null;
                            NetworkCapabilities physicalCaps = null;
                            for (Network n : cm.getAllNetworks()) {
                                NetworkCapabilities c = cm.getNetworkCapabilities(n);
                                if (c != null && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                                    physicalNetwork = n;
                                    physicalCaps = c;
                                    break;
                                }
                            }
                            
                            if (physicalNetwork == null || physicalCaps == null) {
                                listener.updateDefaultInterface("", -1, false, false);
                                return;
                            }
                            
                            LinkProperties lp = cm.getLinkProperties(physicalNetwork);
                            String name = (lp != null && lp.getInterfaceName() != null) ? lp.getInterfaceName() : "";
                            boolean metered = !physicalCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
                            int index = resolveInterfaceIndex(name);
                            
                            listener.updateDefaultInterface(name, index, metered, false);
                        } catch (Exception ignored) {}
                    }
                    @Override public void onAvailable(Network network) { notifyListener(); }
                    @Override public void onLost(Network network) { notifyListener(); }
                    @Override public void onLinkPropertiesChanged(Network network, LinkProperties lp) { notifyListener(); }
                    @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) { notifyListener(); }
                });
                
                // Trigger once immediately
                // Wait for a short delay as we cannot trigger the callback inside the register method
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            Network physicalNetwork = null;
                            NetworkCapabilities physicalCaps = null;
                            for (Network n : cm.getAllNetworks()) {
                                NetworkCapabilities c = cm.getNetworkCapabilities(n);
                                if (c != null && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                                    physicalNetwork = n;
                                    physicalCaps = c;
                                    break;
                                }
                            }
                            if (physicalNetwork != null && physicalCaps != null) {
                                LinkProperties lp = cm.getLinkProperties(physicalNetwork);
                                String name = (lp != null && lp.getInterfaceName() != null) ? lp.getInterfaceName() : "";
                                boolean metered = !physicalCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
                                int index = resolveInterfaceIndex(name);
                                listener.updateDefaultInterface(name, index, metered, false);
                            } else {
                                listener.updateDefaultInterface("", -1, false, false);
                            }
                        } catch (Exception ignored) {}
                    }
                }, 500);
            }
        } catch (Exception e) {
            Log.e(TAG, "startDefaultInterfaceMonitor failed", e);
        }
    }

    @Override
    public void startNeighborMonitor(NeighborUpdateListener listener) {}

    @Override
    public StringIterator systemCertificates() { return null; }

    @Override
    public boolean underNetworkExtension() { return false; }

    @Override
    public boolean usePlatformAutoDetectInterfaceControl() { return true; }

    @Override
    public boolean useProcFS() { return false; }
}
