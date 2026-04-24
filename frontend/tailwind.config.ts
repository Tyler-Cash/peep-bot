import type { Config } from "tailwindcss";

const config: Config = {
  content: ["./src/**/*.{ts,tsx}"],
  darkMode: "class",
  theme: {
    extend: {
      colors: {
        ink: "#0E100D",
        ink2: "#1A1D18",
        ink3: "#262A24",
        paper: "#F5F1E8",
        paper2: "#EEE8DA",
        paper3: "#E4DDC9",
        line: "rgba(14,16,13,0.10)",
        lineDk: "rgba(245,241,232,0.12)",
        mute: "#6B6E66",
        muteDk: "#8F9289",
        leaf: "#7BC24F",
        leafDk: "#4E8A2C",
        leafLt: "#C8E5B0",
        pond: "#2F4A1E",
        discord: "#5865F2",
        // Category palette — bg / ink / dot per category
        "cat-movie-bg": "#FFD89B",
        "cat-movie-ink": "#5A2E08",
        "cat-movie-dot": "#E07A2B",
        "cat-trivia-bg": "#FFF0A6",
        "cat-trivia-ink": "#3D3A14",
        "cat-trivia-dot": "#C9A820",
        "cat-comedy-bg": "#FFB8D9",
        "cat-comedy-ink": "#5A1F3D",
        "cat-comedy-dot": "#D14785",
        "cat-food-bg": "#B8E89A",
        "cat-food-ink": "#1F4410",
        "cat-food-dot": "#5FA838",
        "cat-outdoor-bg": "#A5D8E0",
        "cat-outdoor-ink": "#0E3B44",
        "cat-outdoor-dot": "#2F8296",
        "cat-game-bg": "#D4B8FF",
        "cat-game-ink": "#2E1A5A",
        "cat-game-dot": "#7849D4",
      },
      boxShadow: {
        "chunky-sm": "2px 2px 0 #0E100D",
        chunky: "3px 3px 0 #0E100D",
        "chunky-md": "4px 4px 0 #0E100D",
        "chunky-lg": "5px 5px 0 #0E100D",
        "chunky-leaf": "2px 2px 0 #4E8A2C",
        "chunky-active": "1px 1px 0 #0E100D",
      },
      borderWidth: { "1.5": "1.5px" },
      fontFamily: {
        sans: ['"Space Grotesk"', "ui-sans-serif", "system-ui", "sans-serif"],
      },
      keyframes: {
        "pb-bounce": {
          "0%, 80%, 100%": { transform: "translateY(0)", opacity: "0.4" },
          "40%": { transform: "translateY(-8px)", opacity: "1" },
        },
      },
      animation: { "pb-bounce": "pb-bounce 1.2s infinite ease-in-out both" },
    },
  },
  plugins: [],
};

export default config;
