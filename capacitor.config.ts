import type { CapacitorConfig } from "@capacitor/cli";

const config: CapacitorConfig = {
  appId: "com.sightings.atlas",
  appName: "Sightings",
  webDir: "dist",
  android: {
    allowMixedContent: false,
  },
};

export default config;
