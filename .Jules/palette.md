# Palette's Journal - Peep Bot

## 2026-03-20 - Accessible Character Counters
**Learning:** For textareas with significant character limits (like the 3800-character event description), users benefit from real-time feedback on their remaining allowance. Using `aria-live="polite"` ensures screen reader users are notified of the count without being interrupted, and `aria-describedby` creates a semantic link between the input and the counter.
**Action:** Always implement character counters for long-form text inputs using `react-hook-form`'s `watch` and appropriate ARIA attributes.
