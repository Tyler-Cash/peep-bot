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
import { useActiveGuild, useGuildFeatures } from "@/lib/hooks";
import { StaticMapThumb } from "@/components/ui/StaticMapThumb";

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
  onPick?: (
    placeId: string,
    displayValue: string,
    suggestion: PlaceSuggestion,
  ) => void;
  placeholder?: string;
  required?: boolean;
  className?: string;
  recent?: string[];
  locationBias?: { lat: number; lng: number };
}) {
  const activeGuild = useActiveGuild();
  const { data: features } = useGuildFeatures(activeGuild?.id);
  // Default true while loading (avoids flicker hiding the dropdown on mount)
  const googleEnabled = features ? features.googleAutocompleteEnabled : true;

  const [open, setOpen] = useState(false);
  const [searchResults, setSearchResults] = useState<PlaceSuggestion[]>([]);
  const [highlight, setHighlight] = useState(0);
  const [loading, setLoading] = useState(false);
  const [rateLimitWarning, setRateLimitWarning] = useState(false);
  const [locationUnavailable, setLocationUnavailable] = useState(false);
  // Lazy useState initializer: create the session token once, stable for the
  // component's lifetime (useMemo guarantees neither, and react-hooks/use-memo
  // rejects a non-inline initializer).
  const [sessionToken] = useState(newPlacesSessionToken);
  // `mode` is fully derived from the query, so it never needs to live in state
  // or be assigned inside the search effect (react-hooks/set-state-in-effect).
  const mode: "recent" | "search" = value.trim() ? "search" : "recent";
  const containerRef = useRef<HTMLDivElement | null>(null);
  const inputRef = useRef<HTMLInputElement | null>(null);
  const retryTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const recentSuggestions: PlaceSuggestion[] = useMemo(
    () => (recent ?? []).map((title, i) => ({ id: `recent:${i}`, title })),
    [recent],
  );

  // Displayed list is derived: recent spots when the query is empty, otherwise
  // the search hits. This keeps the empty-query case out of the search effect.
  const suggestions = mode === "recent" ? recentSuggestions : searchResults;

  // Reset the keyboard highlight to the top whenever the query changes. The old
  // effect reset it on every list refresh; doing it during render via a
  // prev-value tracker avoids a set-state-in-effect.
  const [highlightedQuery, setHighlightedQuery] = useState(value);
  if (value !== highlightedQuery) {
    setHighlightedQuery(value);
    setHighlight(0);
  }

  useEffect(() => {
    const onDocClick = (e: MouseEvent) => {
      if (!containerRef.current?.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener("mousedown", onDocClick);
    return () => document.removeEventListener("mousedown", onDocClick);
  }, []);

  useEffect(() => {
    if (!open || !googleEnabled) return;
    const q = value.trim();
    // Empty query → recent mode, rendered from derived state; nothing to fetch.
    if (!q) return;
    const ctrl = new AbortController();

    const t = setTimeout(async () => {
      // Flip the spinner on inside the debounce callback (when the fetch
      // actually starts) rather than synchronously in the effect body — keeps
      // react-hooks/set-state-in-effect happy and avoids a spinner flash while
      // the user is still typing.
      setLoading(true);
      if (retryTimeoutRef.current) {
        clearTimeout(retryTimeoutRef.current);
        retryTimeoutRef.current = null;
      }

      const results = await searchPlaces(value, sessionToken, ctrl.signal, locationBias);

      if (Array.isArray(results)) {
        setSearchResults(results);
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
          setSearchResults(retryResults);
          setHighlight(0);
          setLoading(false);
        }
      }, retryAfter * 1000);
    }, 150);

    return () => {
      clearTimeout(t);
      if (retryTimeoutRef.current) {
        clearTimeout(retryTimeoutRef.current);
        retryTimeoutRef.current = null;
      }
      ctrl.abort();
    };
  }, [value, open, googleEnabled, sessionToken, onChange, locationBias]);

  // When google autocomplete is disabled, render a plain text input
  if (!googleEnabled) {
    return (
      <div className={clsx("relative", className)}>
        <div className="relative">
          <span
            className="absolute left-3 top-1/2 -translate-y-1/2 text-ink/60 pointer-events-none z-[2]"
            aria-hidden
          >
            📍
          </span>
          <input
            value={value}
            required={required}
            placeholder={placeholder ?? "where?"}
            onChange={(e) => onChange(e.target.value)}
            autoComplete="off"
            className={clsx(
              "relative w-full h-12 border-[1.5px] border-ink bg-white pl-10 pr-[14px] text-[16px] font-semibold text-ink placeholder:font-medium placeholder:text-mute focus:outline-none rounded-chip shadow-rest",
            )}
          />
        </div>
      </div>
    );
  }

  const pick = (s: PlaceSuggestion) => {
    const displayValue = suggestionToLocation(s);
    onChange(displayValue);
    fetchPlaceDetails(s.id, sessionToken);
    if (onPick && !s.id.startsWith("recent:")) {
      onPick(s.id, displayValue, s);
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
    !locationUnavailable &&
    open &&
    (suggestions.length > 0 || (loading && mode === "search"));

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
          {loading && mode === "search" && suggestions.length === 0 && (
            <div className="px-3 py-2 text-[13px] text-mute">looking…</div>
          )}
          {suggestions.map((s, i) => {
            const selected = i === highlight;
            const isRecent = s.id.startsWith("recent:");
            return (
              <button
                key={s.id}
                type="button"
                role="option"
                aria-selected={selected}
                onMouseEnter={() => setHighlight(i)}
                onClick={() => pick(s)}
                className={clsx(
                  "w-full text-left px-3.5 py-2.5 flex items-center gap-3 transition-colors",
                  selected ? "bg-[#FFF0A6]" : "bg-white hover:bg-paper",
                )}
                style={
                  i < suggestions.length - 1
                    ? { borderBottom: "1px dashed rgba(14,16,13,0.12)" }
                    : undefined
                }
              >
                {isRecent ? (
                  <span aria-hidden className="shrink-0 text-[16px] w-[52px] h-[52px] flex items-center justify-center">📍</span>
                ) : (
                  <StaticMapThumb placeId={s.id} size={52} />
                )}
                <span className="flex flex-col min-w-0 gap-[2px] flex-1">
                  <span className="text-[15px] font-extrabold text-ink tracking-[-0.01em] leading-[1.15] truncate">
                    {s.title}
                  </span>
                  {s.subtitle && (
                    <span className="text-[12.5px] text-mute font-semibold leading-[1.3] truncate">
                      {s.subtitle}
                    </span>
                  )}
                </span>
                {selected && (
                  <span
                    aria-hidden
                    className="shrink-0 text-[11px] font-extrabold tracking-[0.12em] text-mute uppercase"
                  >
                    ↵
                  </span>
                )}
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
