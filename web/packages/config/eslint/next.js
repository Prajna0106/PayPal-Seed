import nextVitals from "eslint-config-next/core-web-vitals";
import nextTs from "eslint-config-next/typescript";

/** Shared rules for the Next.js apps. */
export default [
  ...nextVitals,
  ...nextTs,
  { ignores: [".next/**", "next-env.d.ts"] },
  {
    rules: {
      "@typescript-eslint/no-explicit-any": "error",
      "@typescript-eslint/no-unused-vars": ["error", { argsIgnorePattern: "^_", varsIgnorePattern: "^_" }],
      "@typescript-eslint/consistent-type-imports": ["error", { fixStyle: "inline-type-imports" }],
      "no-restricted-imports": [
        "error",
        {
          paths: [
            {
              name: "@paypal-seed/api-client",
              message: "Import the backend client only in server code (src/server or features/*/server).",
              allowTypeImports: true,
            },
          ],
        },
      ],
    },
  },
  {
    files: ["src/server/**", "src/features/*/server/**", "src/features/*/actions.ts", "src/proxy.ts", "src/app/**/route.ts"],
    rules: { "no-restricted-imports": "off" },
  },
];
