import type { NextConfig } from "next";

const securityHeaders = [
  { key: "X-Content-Type-Options", value: "nosniff" },
  { key: "Referrer-Policy", value: "strict-origin-when-cross-origin" },
  { key: "X-Frame-Options", value: "DENY" },
  { key: "Permissions-Policy", value: "camera=(), microphone=(), geolocation=(), browsing-topics=()" },
];

/**
 * `pnpm build:lowmem` (SEED_LOW_MEMORY=1) trades build speed for a small footprint: one static-generation
 * worker instead of cores-1, no on-disk compile cache, no source maps, and no TypeScript pass (run
 * `pnpm typecheck` separately; it is the same compiler).
 */
const lowMemory = process.env.SEED_LOW_MEMORY === "1";

const nextConfig: NextConfig = {
  reactStrictMode: true,
  poweredByHeader: false,
  typedRoutes: true,
  transpilePackages: ["@paypal-seed/ui", "@paypal-seed/api-client", "@paypal-seed/timeline"],
  ...(lowMemory && {
    productionBrowserSourceMaps: false,
    enablePrerenderSourceMaps: false,
    typescript: { ignoreBuildErrors: true },
    experimental: {
      cpus: 1,
      memoryBasedWorkersCount: true,
      webpackMemoryOptimizations: true,
      turbopackFileSystemCacheForBuild: false,
      serverSourceMaps: false,
    },
  }),
  async headers() {
    return [{ source: "/:path*", headers: securityHeaders }];
  },
};

export default nextConfig;
