import type { Category } from "./types";

export const CATEGORIES: Record<
  Category,
  { emoji: string; label: string; bg: string; ink: string; dot: string }
> = {
  movie: {
    emoji: "🎬",
    label: "movie",
    bg: "#FFD89B",
    ink: "#5A2E08",
    dot: "#E07A2B",
  },
  trivia: {
    emoji: "🧠",
    label: "trivia",
    bg: "#FFF0A6",
    ink: "#3D3A14",
    dot: "#C9A820",
  },
  comedy: {
    emoji: "🎤",
    label: "comedy",
    bg: "#FFB8D9",
    ink: "#5A1F3D",
    dot: "#D14785",
  },
  food: {
    emoji: "🍜",
    label: "food",
    bg: "#B8E89A",
    ink: "#1F4410",
    dot: "#5FA838",
  },
  outdoor: {
    emoji: "🥾",
    label: "outdoor",
    bg: "#A5D8E0",
    ink: "#0E3B44",
    dot: "#2F8296",
  },
  game: {
    emoji: "🎲",
    label: "game",
    bg: "#D4B8FF",
    ink: "#2E1A5A",
    dot: "#7849D4",
  },
};

export function categoryMeta(c: Category | string | undefined) {
  return CATEGORIES[(c as Category) ?? "outdoor"] ?? CATEGORIES.outdoor;
}
