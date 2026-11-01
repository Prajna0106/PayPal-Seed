import { ImageResponse } from "next/og";

export const alt = "PayPal Seed: fair credit for the people banks overlook. Save from $5 a month, unlock a 0% loan.";
export const size = { width: 1200, height: 630 };
export const contentType = "image/png";

// Colours mirror the design tokens in @paypal-seed/ui (Satori cannot read CSS variables).
const CANVAS = "#f5f5f5";
const INK = "#0c0a09";
const BODY = "#4e4e4e";

export default function OpengraphImage() {
  return new ImageResponse(
    (
      <div
        style={{
          width: "100%",
          height: "100%",
          display: "flex",
          flexDirection: "column",
          justifyContent: "space-between",
          padding: "72px 80px",
          backgroundColor: CANVAS,
          backgroundImage:
            "radial-gradient(circle at 12% 18%, #a7e5d3 0%, rgba(168,200,232,0.55) 22%, transparent 45%), radial-gradient(circle at 92% 88%, #f4c5a8 0%, rgba(232,184,196,0.5) 20%, transparent 42%)",
          color: INK,
        }}
      >
        <div style={{ display: "flex", alignItems: "center", gap: 16, fontSize: 34 }}>
          <div
            style={{
              width: 44,
              height: 44,
              borderRadius: 9999,
              backgroundImage: "radial-gradient(circle at 35% 30%, #a7e5d3, #a8c8e8 55%, #c8b8e0)",
            }}
          />
          <span>PayPal Seed</span>
        </div>
        <div style={{ display: "flex", flexDirection: "column", gap: 24 }}>
          <div style={{ fontSize: 68, lineHeight: 1.05, letterSpacing: -2, maxWidth: 980 }}>
            Fair credit for the people banks overlook.
          </div>
          <div style={{ fontSize: 28, color: BODY }}>
            Save from $5 a month · 0% loans · no credit score · no fees · inside PayPal
          </div>
        </div>
      </div>
    ),
    { ...size },
  );
}
