// TypeScript 6 enforces `noUncheckedSideEffectImports` (TS2882) on bare side-effect
// imports without type declarations. Next.js only ships declarations for *.module.css,
// not plain *.css, so declare the latter here for `import "./globals.css"` in layout.tsx.
declare module "*.css";
