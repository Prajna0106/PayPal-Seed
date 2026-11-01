import library from "@paypal-seed/config/eslint/library";

export default [
  ...library,
  {
    // shadcn components are vendored; keep them close to upstream.
    files: ["src/components/**"],
    rules: { "@typescript-eslint/consistent-type-imports": "off" },
  },
];
