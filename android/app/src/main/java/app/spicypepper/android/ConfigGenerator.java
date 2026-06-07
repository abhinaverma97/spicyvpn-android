package app.spicypepper.android;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;

public class ConfigGenerator {

    public static String generate(String vlessUri) throws Exception {
        URI uri = new URI(vlessUri);

        String uuid = uri.getUserInfo();
        String host = uri.getHost();
        if (host == null) host = "140.245.13.64";
        int port = uri.getPort();
        if (port <= 0) port = 8444;

        String query = uri.getQuery();
        boolean allowInsecure = false;
        String serverName = "spicypepper.app";

        if (query != null) {
            String[] pairs = query.split("&");
            for (String pair : pairs) {
                String[] kv = pair.split("=", 2);
                if (kv.length == 2) {
                    if (kv[0].equals("allowInsecure") && (kv[1].equals("1") || kv[1].equals("true"))) {
                        allowInsecure = true;
                    }
                    if (kv[0].equals("sni")) {
                        serverName = kv[1];
                    }
                }
            }
        }

        JSONObject config = new JSONObject();

        JSONObject log = new JSONObject();
        log.put("level", "info");
        config.put("log", log);

        JSONObject dns = new JSONObject();
        JSONArray dnsServers = new JSONArray();

        JSONObject remoteDns = new JSONObject();
        remoteDns.put("tag", "dns-remote");
        remoteDns.put("type", "udp");
        remoteDns.put("server", "1.1.1.1");
        dnsServers.put(remoteDns);

        JSONObject directDns = new JSONObject();
        directDns.put("tag", "dns-direct");
        directDns.put("type", "udp");
        directDns.put("server", "8.8.8.8");
        dnsServers.put(directDns);

        dns.put("servers", dnsServers);

        dns.put("final", "dns-remote");
        dns.put("strategy", "prefer_ipv4");

        config.put("dns", dns);

        JSONArray inbounds = new JSONArray();
        JSONObject tunInbound = new JSONObject();
        tunInbound.put("type", "tun");
        tunInbound.put("tag", "tun-in");
        tunInbound.put("interface_name", "SpicyVPN-TUN");
        JSONArray addressArray = new JSONArray();
        addressArray.put("172.19.0.1/30");
        tunInbound.put("address", addressArray);
        tunInbound.put("auto_route", true);
        tunInbound.put("strict_route", true);
        tunInbound.put("stack", "gvisor");
        tunInbound.put("mtu", 1350);
        inbounds.put(tunInbound);
        config.put("inbounds", inbounds);

        JSONArray outbounds = new JSONArray();

        JSONObject proxy = new JSONObject();
        proxy.put("type", "vless");
        proxy.put("tag", "proxy");
        proxy.put("server", host);
        proxy.put("server_port", port);
        proxy.put("uuid", uuid);
        proxy.put("packet_encoding", "xudp");
        proxy.put("domain_resolver", "dns-direct");

        JSONObject tls = new JSONObject();
        tls.put("enabled", true);
        tls.put("server_name", serverName);
        tls.put("insecure", allowInsecure);
        JSONArray alpn = new JSONArray();
        alpn.put("h2");
        tls.put("alpn", alpn);
        proxy.put("tls", tls);

        JSONObject transport = new JSONObject();
        transport.put("type", "grpc");
        transport.put("service_name", "spicypepper-grpc");
        transport.put("idle_timeout", "15s");
        transport.put("ping_timeout", "15s");
        proxy.put("transport", transport);

        outbounds.put(proxy);

        JSONObject direct = new JSONObject();
        direct.put("type", "direct");
        direct.put("tag", "direct");
        outbounds.put(direct);



        config.put("outbounds", outbounds);

        JSONObject route = new JSONObject();
        route.put("auto_detect_interface", true);

        JSONArray routeRules = new JSONArray();

        JSONObject dnsRuleRoute = new JSONObject();
        JSONArray tunInboundArr = new JSONArray();
        tunInboundArr.put("tun-in");
        dnsRuleRoute.put("inbound", tunInboundArr);
        dnsRuleRoute.put("protocol", "dns");
        dnsRuleRoute.put("action", "hijack-dns");
        routeRules.put(dnsRuleRoute);

        JSONObject privateRule = new JSONObject();
        privateRule.put("ip_is_private", true);
        privateRule.put("outbound", "direct");
        routeRules.put(privateRule);

        JSONObject serverRule = new JSONObject();
        JSONArray serverCidr = new JSONArray();
        serverCidr.put(host);
        serverCidr.put("8.8.8.8/32");
        serverRule.put("ip_cidr", serverCidr);
        serverRule.put("outbound", "direct");
        routeRules.put(serverRule);

        route.put("rules", routeRules);
        route.put("final", "proxy");
        route.put("default_domain_resolver", "dns-direct");

        config.put("route", route);

        return config.toString(2);
    }
}
