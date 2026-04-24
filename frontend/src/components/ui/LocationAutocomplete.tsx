"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import clsx from "@/lib/clsx";
import {
  fetchPlaceDetails,
  newPlacesSessionToken,
  searchPlaces,
  suggestionToLocation,
  type PlaceSuggestion,
} from "@/lib/places";

export function LocationAutocomplete({
  value,
  onChange,
  placeholder,
  required,
  className,
  recent,
}: {
  value: string;
  onChange: (value: string) => void;
  placeholder?: string;
  required?: boolean;
  className?: string;
  recent?: string[];
}) {
  const [open, setOpen] = useState(false);
  const [suggestions, setSuggestions] = useState<PlaceSuggestion[]>([]);
  const [highlight, setHighlight] = useState(0);
  const [loading, setLoading] = useState(false);
  const [mode, setMode] = useState<"recent" | "search">("recent");
  const [rateLimitWarning, setRateLimitWarning] = useState(false);
  const [locationUnavailable, setLocationUnavailable] = useState(false);
  const sessionToken = useMemo(newPlacesSessionToken, []);
  const containerRef = useRef<HTMLDivElement | null>(null);
  const inputRef = useRef<HTMLInputElement | null>(null);
  const retryTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const recentSuggestions: PlaceSuggestion[] = useMemo(
    () => (recent ?? []).map((title, i) => ({ id: `recent:${i}`, title })),
    [recent],
  );

  useEffect(() => {
    const onDocClick = (e: MouseEvent) => {
      if (!containerRef.current?.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener("mousedown", onDocClick);
    return () => document.removeEventListener("mousedown", onDocClick);
  }, []);

  useEffect(() => {
    if (!open) return;
    const q = value.trim();
    if (!q) {
      setMode("recent");
      setSuggestions(recentSuggestions);
      setHighlight(0);
      setLoading(false);
      return;
    }
    setMode("search");
    const ctrl = new AbortController();
    setLoading(true);

    const t = setTimeout(async () => {
      if (retryTimeoutRef.current) {
        clearTimeout(retryTimeoutRef.current);
        retryTimeoutRef.current = null;
      }

      const results = await searchPlaces(value, sessionToken, ctrl.signal);

      if (Array.isArray(results)) {
        setSuggestions(results);
        setHighlight(0);
        setLoading(false);
        setRateLimitWarning(false);
        setLocationUnavailable(false);
        return;
      }

      const { retryAfter } = results;

      if (retryAfter > 60) {
        setLocationUnavailable(true);
        setLoading(false);
        setOpen(false);
        onChange("");
      } else {
        setRateLimitWarning(retryAfter > 10);
      }

      retryTimeoutRef.current = setTimeout(async () => {
        retryTimeoutRef.current = null;
        setRateLimitWarning(false);

        if (retryAfter > 60) {
          setLocationUnavailable(false);
          return;
        }

        const retryResults = await searchPlaces(value, sessionToken);
        if (Array.isArray(retryResults)) {
          setSuggestions(retryResults);
          setHighlight(0);
          setLoading(false);
        }
      }, retryAfter * 1000);
    }, 300);

    return () => {
      clearTimeout(t);
      if (retryTimeoutRef.current) {
        clearTimeout(retryTimeoutRef.current);
        retryTimeoutRef.current = null;
      }
      ctrl.abort();
    };
  }, [value, open, sessionToken, recentSuggestions, onChange]);

  const pick = (s: PlaceSuggestion) => {
    onChange(suggestionToLocation(s));
    fetchPlaceDetails(s.id, sessionToken);
    setOpen(false);
    inputRef.current?.blur();
  };

  const onKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === "Escape") {
      setOpen(false);
      return;
    }
    if (!open && (e.key === "ArrowDown" || e.key === "ArrowUp")) {
      setOpen(true);
      return;
    }
    if (e.key === "ArrowDown") {
      e.preventDefault();
      setHighlight((h) => Math.min(h + 1, suggestions.length - 1));
    } else if (e.key === "ArrowUp") {
      e.preventDefault();
      setHighlight((h) => Math.max(h - 1, 0));
    } else if (e.key === "Enter" && open && suggestions[highlight]) {
      e.preventDefault();
      pick(suggestions[highlight]);
    }
  };

  return (
    <div ref={containerRef} className={clsx("relative", className)}>
      <div className="relative">
        <span
          className="absolute left-3 top-1/2 -translate-y-1/2 text-ink/60 pointer-events-none"
          aria-hidden
        >
          📍
        </span>
        <input
          ref={inputRef}
          value={value}
          required={required}
          disabled={locationUnavailable}
          placeholder={placeholder ?? "where?"}
          onChange={(e) => {
            onChange(e.target.value);
            setOpen(true);
          }}
          onFocus={() => setOpen(true)}
          onKeyDown={onKeyDown}
          autoComplete="off"
          aria-autocomplete="list"
          aria-expanded={open}
          role="combobox"
          className={clsx(
            "w-full rounded-[10px] border-[1.5px] border-ink bg-paper pl-9 pr-3 py-2 text-[15px] font-medium shadow-chunky-sm focus:outline-none focus:shadow-chunky-md",
            locationUnavailable && "opacity-50 cursor-not-allowed",
          )}
        />
      </div>

      {locationUnavailable && (
        <p className="mt-1.5 text-[12px] font-semibold text-mute">
          location unavailable — too many searches, try again later
        </p>
      )}

      {!locationUnavailable && open && (suggestions.length > 0 || loading) && (
        <div
          role="listbox"
          className="absolute z-30 left-0 right-0 mt-1.5 rounded-[12px] border-[1.5px] border-ink bg-paper shadow-chunky-md overflow-hidden"
        >
          {mode === "recent" && suggestions.length > 0 && (
            <div className="px-3 pt-2 pb-1 text-[10.5px] font-extrabold tracking-[0.18em] text-mute uppercase border-b-[1px] border-ink/10">
              usual spots
            </div>
          )}
          {rateLimitWarning && (
            <div className="px-3 py-2 text-[13px] text-mute border-b-[1px] border-ink/10">
              ⏱ too many searches — try again shortly
            </div>
          )}
          {loading && suggestions.length === 0 && (
            <div className="px-3 py-2 text-[13px] text-mute">looking…</div>
          )}
          {suggestions.map((s, i) => (
            <button
              key={s.id}
              type="button"
              role="option"
              aria-selected={i === highlight}
              onMouseEnter={() => setHighlight(i)}
              onClick={() => pick(s)}
              className={clsx(
                "w-full text-left px-3 py-2 flex flex-col gap-0.5 border-b-[1px] border-ink/10 last:border-b-0",
                i === highlight ? "bg-leaf/15" : "bg-paper hover:bg-paper2",
              )}
            >
              <span className="text-[14px] font-extrabold text-ink tracking-[-0.01em]">
                📍 {s.title}
              </span>
              {s.subtitle && (
                <span className="text-[12.5px] text-mute font-semibold pl-[22px]">
                  {s.subtitle}
                </span>
              )}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
