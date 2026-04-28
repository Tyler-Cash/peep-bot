import type { Category } from "./types";

export const CATEGORIES: Record<
  Category | "unknown",
  { emoji?: string; image?: string; label: string; bg: string; ink: string; dot: string }
> = {
  movie: {
    emoji: "🎬",
    image: "/peepos/peepo-movie.png",
    label: "movie",
    bg: "#FFD89B",
    ink: "#5A2E08",
    dot: "#E07A2B",
  },
  trivia: {
    emoji: "🧠",
    image: "/peepos/peepo-trivia.png",
    label: "trivia",
    bg: "#FFF0A6",
    ink: "#3D3A14",
    dot: "#C9A820",
  },
  comedy: {
    emoji: "🎤",
    image: "/peepos/peepo-comedy.png",
    label: "comedy",
    bg: "#FFB8D9",
    ink: "#5A1F3D",
    dot: "#D14785",
  },
  food: {
    emoji: "🍜",
    image: "/peepos/peepo-food.png",
    label: "food",
    bg: "#B8E89A",
    ink: "#1F4410",
    dot: "#5FA838",
  },
  outdoor: {
    emoji: "🥾",
    image: "/peepos/peepo-outdoor.png",
    label: "outdoor",
    bg: "#A5D8E0",
    ink: "#0E3B44",
    dot: "#2F8296",
  },
  game: {
    emoji: "🎲",
    image: "/peepos/peepo-game.png",
    label: "game",
    bg: "#D4B8FF",
    ink: "#2E1A5A",
    dot: "#7849D4",
  },
  unknown: {
    label: "unknown",
    bg: "#E5E7EB",
    ink: "#374151",
    dot: "#9CA3AF",
  }
};

export function categoryMeta(c: Category | string | undefined) {
  if (!c) return CATEGORIES.trivia;
  // Direct match (mock enum values)
  if (CATEGORIES[c as Category]) return CATEGORIES[c as Category];
  // Fuzzy match from backend free-form labels (e.g., "trivia night" → trivia)
  const lower = c.toLowerCase();
  for (const [key, meta] of Object.entries(CATEGORIES)) {
    if (lower.includes(key)) return meta;
  }
  return CATEGORIES.unknown;
}
