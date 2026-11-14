import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// PREVIEW_HOST is supplied per-process by the cloud preview environment so the VM's
// preview hostname never has to be committed to this config.
const previewHost = process.env.PREVIEW_HOST;

export default defineConfig({
  plugins: [react()],
  server: {
    host: "0.0.0.0",
    port: 3000,
    strictPort: true,
    allowedHosts: previewHost ? [previewHost] : [],
  },
});
