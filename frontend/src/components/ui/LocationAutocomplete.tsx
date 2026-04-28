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
  onPick,
  placeholder,
  required,
  className,
  recent,
  locationBias,
}: {
  value: string;
  onChange: (value: string) => void;
  onPick?: (placeId: string, displayValue: string) => void;
  placeholder?: string;
  required?: boolean;
  className?: string;
  recent?: string[];
  locationBias?: { lat: number; lng: number };
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

      const results = await searchPlaces(value, sessionToken, ctrl.signal, locationBias);

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

        const retryResults = await searchPlaces(value, sessionToken, undefined, locationBias);
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
  }, [value, open, sessionToken, recentSuggestions, onChange, locationBias]);

  const pick = (s: PlaceSuggestion) => {
    const displayValue = suggestionToLocation(s);
    onChange(displayValue);
    fetchPlaceDetails(s.id, sessionToken);
    if (onPick && !s.id.startsWith("recent:")) {
      onPick(s.id, displayValue);
    }
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

  const popoverOpen =
    !locationUnavailable && open && (suggestions.length > 0 || loading);

  return (
    <div ref={containerRef} className={clsx("relative", className)}>
      <div className="relative">
        <span
          className="absolute left-3 top-1/2 -translate-y-1/2 text-ink/60 pointer-events-none z-[2]"
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
          aria-controls={open ? "location-suggestions" : undefined}
          role="combobox"
          className={clsx(
            "relative w-full h-12 border-[1.5px] border-ink bg-white pl-10 pr-[14px] text-[16px] font-semibold text-ink placeholder:font-medium placeholder:text-mute focus:outline-none",
            popoverOpen
              ? "rounded-t-chip rounded-b-none"
              : "rounded-chip shadow-rest",
            locationUnavailable && "opacity-50 cursor-not-allowed",
          )}
          style={
            popoverOpen
              ? {
                  boxShadow: "3px 0 0 #0E100D",
                  borderBottomStyle: "dashed",
                  borderBottomWidth: "1px",
                  borderBottomColor: "rgba(14,16,13,0.2)",
                }
              : undefined
          }
        />
      </div>

      {locationUnavailable && (
        <p className="mt-1.5 text-[12px] font-semibold text-mute">
          location unavailable — too many searches, try again later
        </p>
      )}

      {popoverOpen && (
        <div
          id="location-suggestions"
          role="listbox"
          className="absolute top-full left-0 right-0 z-30 rounded-b-chip border-[1.5px] border-t-0 border-ink bg-white shadow-[3px_0_0_#0E100D,0_3px_0_#0E100D] overflow-hidden"
        >
          {mode === "recent" && suggestions.length > 0 && (
            <div
              className="px-3 pt-2 pb-1 text-[10.5px] font-extrabold tracking-[0.18em] text-mute uppercase"
              style={{ borderBottom: "1px dashed rgba(14,16,13,0.18)" }}
            >
              usual spots
            </div>
          )}
          {rateLimitWarning && (
            <div
              className="px-3 py-2 text-[13px] text-mute"
              style={{ borderBottom: "1px dashed rgba(14,16,13,0.18)" }}
            >
              ⏱ too many searches — try again shortly
            </div>
          )}
          {loading && suggestions.length === 0 && (
            <div className="px-3 py-2 text-[13px] text-mute">looking…</div>
          )}
          {suggestions.map((s, i) => {
            const selected = i === highlight;
            return (
              <button
                key={s.id}
                type="button"
                role="option"
                aria-selected={selected}
                onMouseEnter={() => setHighlight(i)}
                onClick={() => pick(s)}
                className={clsx(
                  "w-full text-left px-3 py-2.5 flex items-center gap-3 transition-colors",
                  selected ? "bg-[#FFF0A6]" : "bg-white hover:bg-paper",
                )}
                style={
                  i < suggestions.length - 1
                    ? { borderBottom: "1px dashed rgba(14,16,13,0.12)" }
                    : undefined
                }
              >
                <span aria-hidden className="shrink-0 text-[16px]">📍</span>
                <span className="flex flex-col min-w-0 gap-[2px]">
                  <span className="text-[15px] font-extrabold text-ink tracking-[-0.01em] leading-[1.15] truncate">
                    {s.title}
                  </span>
                  {s.subtitle && (
                    <span className="text-[12.5px] text-mute font-semibold leading-[1.3] truncate">
                      {s.subtitle}
                    </span>
                  )}
                </span>
              </button>
            );
          })}
          {suggestions.length > 0 && mode === "search" && (
            <div className="flex items-center justify-end gap-1.5 px-3 py-2 bg-paper border-t-[1.5px] border-ink text-[11px] font-bold tracking-[0.12em] text-mute uppercase">
              powered by google maps
            </div>
          )}
        </div>
      )}
    </div>
  );
}
