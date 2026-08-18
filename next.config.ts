import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // WebContainers (in-browser code runner / cloud terminal) require the page
  // to be cross-origin isolated.
  async headers() {
    return [
      {
        source: "/:path*",
        headers: [
          { key: "Cross-Origin-Opener-Policy", value: "same-origin" },
          { key: "Cross-Origin-Embedder-Policy", value: "credentialless" },
        ],
      },
    ];
  },
};

export default nextConfig;
