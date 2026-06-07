package app.spicypepper.android;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        this.initialPlugins.add(SpicyVPNPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
