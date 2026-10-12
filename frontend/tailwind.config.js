/** @type {import('tailwindcss').Config} */
export default {
  content: ["./index.html", "./src/**/*.{ts,tsx}"],
  theme: {
    extend: {
      fontFamily: {
        sans: ["Inter", "ui-sans-serif", "system-ui", "sans-serif"],
      },
      colors: {
        brand: {
          forest: "#163300",
          neon: "#9FE870",
          neonHover: "#8CD65F",
          sand: "#F4F5F0",
          surface: "#FFFFFF",
          charcoal: "#1B1C1D",
          subtle: "#5D7052",
          border: "#E2E6DC",
          warning: "#E58300",
          danger: "#D9381E",
          dangerSurface: "#FDF2F0",
        },
        wise: {
          forest: "#163300",
          neon: "#9FE870",
          canvas: "#F4F5F0",
          ink: "#0E150E",
          subtext: "#5C685B",
          blocked: { text: "#BA1A1A", bg: "#FFDAD6" },
          settled: { text: "#0F5132", bg: "#D1E7DD" },
          vault: { text: "#084298", bg: "#CFF4FC" },
        },
      },
      borderRadius: {
        "2xl": "16px",
        "3xl": "24px",
        pill: "9999px",
      },
      boxShadow: {
        card: "0 2px 8px rgba(22, 51, 0, 0.04), 0 1px 2px rgba(22, 51, 0, 0.02)",
        elevated:
          "0 12px 32px rgba(22, 51, 0, 0.08), 0 2px 6px rgba(22, 51, 0, 0.04)",
        modal: "0 24px 48px -12px rgba(22, 51, 0, 0.18)",
      },
    },
  },
  plugins: [],
};
