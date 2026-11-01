// Low-memory production build for laptops: `pnpm --filter @paypal-seed/<app> build:lowmem`.
//
// Why: `next build` defaults to Turbopack, which compiles on every core at once, and then starts
// (cores - 1) workers to prerender pages. On a 12-core laptop with a few GB free that is enough to freeze
// Windows. This runs webpack (heap-capped, with Next's memory optimisations), one prerender worker,
// at below-normal CPU priority so the desktop stays responsive.
import { spawn } from "node:child_process";
import { createRequire } from "node:module";
import os from "node:os";
import path from "node:path";

const heapMb = process.env.SEED_BUILD_HEAP_MB ?? "1536";
const require = createRequire(path.join(process.cwd(), "package.json"));
const nextBin = path.join(path.dirname(require.resolve("next/package.json")), "dist", "bin", "next");

const child = spawn(process.execPath, [`--max-old-space-size=${heapMb}`, nextBin, "build", "--webpack"], {
  stdio: "inherit",
  env: { ...process.env, SEED_LOW_MEMORY: "1", NEXT_TELEMETRY_DISABLED: "1" },
});

try {
  os.setPriority(child.pid, os.constants.priority.PRIORITY_BELOW_NORMAL);
} catch {
  // Priority is a courtesy; the build still runs without it.
}

child.on("exit", (code, signal) => {
  process.exit(code ?? (signal ? 1 : 0));
});
