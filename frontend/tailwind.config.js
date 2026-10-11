/** @type {import('tailwindcss').Config} */
export default {
  content: ["./index.html", "./src/**/*.{ts,tsx}"],
  theme: {
    extend: {
      colors: {
        escrowrail: {
          forest: "#163300",
          neon: "#9FE870",
          canvas: "#F4F5F7",
          ink: "#0E150E",
          subtext: "#5C685B",
          blocked: { text: "#BA1A1A", bg: "#FFDAD6" },
          settled: { text: "#0F5132", bg: "#D1E7DD" },
          vault: { text: "#084298", bg: "#CFF4FC" },
        },
      },
    },
  },
  plugins: [],
};
