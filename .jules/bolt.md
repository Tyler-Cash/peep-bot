## 2026-03-24 - [Unnecessary re-sorting and re-formatting in lists]
**Learning:** In `ListEvents.jsx`, the event list was being sorted on every render using `moment.js`, which is an expensive operation. Additionally, `EventOverview.jsx` was performing date formatting (including `moment.tz.guess(true)`) on every render.
**Action:** Use `useMemo` for sorting lists and formatting dates. Wrap list items in `React.memo` to prevent cascading re-renders.

## 2026-03-24 - [Importing combineReducers from redux vs @reduxjs/toolkit]
**Learning:** In a project using `@reduxjs/toolkit`, importing `combineReducers` from `redux` directly can cause build failures in some environments (like `pnpm` with strict dependency resolution) if `redux` is not explicitly listed as a dependency.
**Action:** Always import `combineReducers` from `@reduxjs/toolkit` instead of `redux` to ensure dependency alignment and fix build resolution failures.
