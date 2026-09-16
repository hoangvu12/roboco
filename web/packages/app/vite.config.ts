import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import { resolve } from "node:path";

const engine = process.env.ROBOCO_DEV_ENGINE ?? "http://127.0.0.1:27699";

export default defineConfig({
  plugins: [react()],
  build: {
    rollupOptions: {
      input: {
        index: resolve(__dirname, "index.html"),
        pair: resolve(__dirname, "pair.html"),
      },
    },
  },
  server: {
    proxy: {
      "/pairing/redeem": { target: engine },
      "/": {
        target: engine,
        ws: true,
        bypass: (req) => {
          if (req.headers.upgrade === "websocket") {
            return undefined;
          }
          return req.url;
        },
      },
    },
  },
});
