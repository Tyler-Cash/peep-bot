// OTel service.name for the frontend. Matches the backend's peepbot-* naming
// (peepbot-backend / peepbot-staging-backend) so every span shares one scheme in
// Tempo + spanmetrics (where env is encoded in the service name, not a label).
// Override per environment via NEXT_PUBLIC_OTEL_SERVICE_NAME — e.g. set it to
// "peepbot-staging-frontend" in the staging Vercel project. NEXT_PUBLIC_ so the
// same value is available to both the server runtime and the browser bundle.
export const SERVICE_NAME =
  process.env.NEXT_PUBLIC_OTEL_SERVICE_NAME ?? "peepbot-frontend";
