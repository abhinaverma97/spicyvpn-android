package app.spicypepper.android;

import android.util.Base64;

import com.getcapacitor.JSObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class SubscriptionManager {

    public static String resolveUri(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", "SpicyVPN Android");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);

        int responseCode = conn.getResponseCode();
        if (responseCode != HttpURLConnection.HTTP_OK) {
            BufferedReader errReader = new BufferedReader(
                    new InputStreamReader(conn.getErrorStream() != null ?
                            conn.getErrorStream() : conn.getInputStream()));
            StringBuilder errMsg = new StringBuilder();
            String line;
            while ((line = errReader.readLine()) != null) errMsg.append(line);
            errReader.close();
            String msg = errMsg.toString().isEmpty() ?
                    "Subscription is inactive or expired" : errMsg.toString();
            throw new Exception(msg);
        }

        BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream()));
        StringBuilder body = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) body.append(line);
        reader.close();
        conn.disconnect();

        String b64Body = body.toString().trim();
        byte[] decoded = Base64.decode(b64Body, Base64.DEFAULT);
        return new String(decoded, StandardCharsets.UTF_8);
    }

    public static JSObject fetch(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", "SpicyVPN Android");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);

        int responseCode = conn.getResponseCode();
        if (responseCode != HttpURLConnection.HTTP_OK) {
            BufferedReader errReader = new BufferedReader(
                    new InputStreamReader(conn.getErrorStream() != null ?
                            conn.getErrorStream() : conn.getInputStream()));
            StringBuilder errMsg = new StringBuilder();
            String line;
            while ((line = errReader.readLine()) != null) errMsg.append(line);
            errReader.close();
            String msg = errMsg.toString().isEmpty() ?
                    "Subscription is inactive or expired" : errMsg.toString();
            throw new Exception(msg);
        }

        String info = conn.getHeaderField("subscription-userinfo");
        if (info == null) info = "upload=0; download=0; total=0; expire=0";

        String b64Name = conn.getHeaderField("x-user-name");
        String email = conn.getHeaderField("x-user-email");
        if (email == null) email = "";

        String name = "";
        if (b64Name != null && !b64Name.isEmpty()) {
            try {
                byte[] decoded = Base64.decode(b64Name, Base64.DEFAULT);
                name = new String(decoded, StandardCharsets.UTF_8);
            } catch (Exception ignored) {}
        }

        long upload = 0, download = 0, total = 0, expire = 0;
        String[] parts = info.split(";");
        for (String part : parts) {
            String[] kv = part.split("=");
            if (kv.length == 2) {
                try {
                    long v = Long.parseLong(kv[1].trim());
                    switch (kv[0].trim()) {
                        case "upload": upload = v; break;
                        case "download": download = v; break;
                        case "total": total = v; break;
                        case "expire": expire = v; break;
                    }
                } catch (NumberFormatException ignored) {}
            }
        }

        conn.disconnect();

        JSObject stats = new JSObject();
        stats.put("upload", upload);
        stats.put("download", download);
        stats.put("total", total);
        stats.put("expire", expire);
        stats.put("name", name);
        stats.put("email", email);
        return stats;
    }
}
