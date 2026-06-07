import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'app.spicypepper.android',
  appName: 'SpicyVPN',
  webDir: 'dist',
  server: {
    androidScheme: 'https',
  },
};

export default config;
