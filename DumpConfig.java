package app.spicypepper.android;
public class DumpConfig {
    public static void main(String[] args) throws Exception {
        System.out.println(ConfigGenerator.generate("vless://fake-uuid@140.245.13.64:8444?security=tls&sni=spicypepper.app&allowInsecure=1#SpicyVPN"));
    }
}
