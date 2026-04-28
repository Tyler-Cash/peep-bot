# Staging → Production Promotion Pipeline

## Problem

The current `ci.yml` deploys the backend to a staging Portainer stack and then deploys the frontend straight to Vercel production. There is no production-backend deploy, no staging-frontend deploy, no verification gate, and the same artifact is not reused across environments. The current run is also failing immediately because `PORTAINER_DEPLOY_TOKEN` is not set in repo secrets, causing `actions/checkout` of `Tyler-Cash/portainer` to error with `Input required and not supplied: token`.

## Goals

- One push to `main` produces a single set of immutable artifacts (one backend image digest, one Vercel deployment) and promotes them through staging and then production.
- Backend deploys before frontend in each environment so new API contracts are live before the frontend that depends on them.
- A manual approval gate sits between staging and production so a human can eyeball staging before promoting.
- Same artifact bytes go to prod that were verified in staging — no rebuilds between environments.
- Hobby-project scope: no auto-rollback, no smoke checks (yet), no PR previews (yet). Fix-forward only.

## Non-goals

- Automated post-deploy health/smoke checks. (Manual gate covers this for now.)
- Automatic rollback on prod failure.
- PR-triggered preview deploys to Vercel or staging Portainer.
- Multi-region or canary rollouts.

## Pipeline shape

```
backend-test, backend-e2etest, backend-build, backend-sbom,
frontend-ci, frontend-sbom              (existing, unchanged structurally)
        │
        ▼
backend-push                             (existing — pushes :latest AND :<version> to GHCR)
        │
        ▼
release                                  (existing — semantic-release, emits new_release_version)
        │
        ▼
deploy-staging-backend                   (commits stacks/peepbot-staging/docker-compose.yml,
                                          waits for portainer deploy.yml run, exports
                                          version@digest as job output)
        │
        ▼
deploy-staging-frontend                  (vercel deploy preview, alias to
                                          event-staging.tylercash.dev, exports deployment URL)
        │
        ▼
   ┌─────────────────────────────┐
   │  Manual gate                │   GitHub environment `production` with required reviewer
   │  (GH environment approval)  │
   └─────────────────────────────┘
        │
        ▼
deploy-prod-backend                      (commits stacks/peepbot/docker-compose.yml using the
                                          SAME version@digest from staging, waits for portainer)
        │
        ▼
deploy-prod-frontend                     (vercel promote <staging-url> — no rebuild)
```

All deploy jobs run only on `push` to `main`. PRs continue to run tests, lint, build, and SBOM scans without any deploy.

## Artifact reuse

### Backend image

The existing pipeline relies on the `:<version>` tag existing in GHCR by the time `deploy-staging-backend` runs. That tag is produced by `semantic-release` (via the `@semantic-release/exec` plugin already configured in the `release` job). We do not need to change the build/push/release jobs — only how the resulting tag is consumed.

`deploy-staging-backend` (existing job, with one addition):

1. Pulls `ghcr.io/<owner>/peep-bot-backend:<version>`.
2. Resolves the digest: `docker inspect --format='{{index .RepoDigests 0}}' | cut -d@ -f2`.
3. Writes `image: ghcr.io/<owner>/peep-bot-backend:<version>@<digest>` into `stacks/peepbot-staging/docker-compose.yml`.
4. Commits and pushes to `Tyler-Cash/portainer`, waits for the portainer `deploy.yml` workflow to finish for that commit.
5. **New:** exports `image_ref` (the full `<version>@<digest>` string) as a job output.

`deploy-prod-backend` reads `image_ref` from the staging job's outputs and runs steps 3–4 against `stacks/peepbot/docker-compose.yml`. No second `docker pull`, no second digest lookup — the bytes are guaranteed identical to staging.

### Frontend (Vercel)

`deploy-staging-frontend`:

1. `vercel pull --environment=production` (Vercel "production" env settings — staging is just an alias on the same project, not a separate env).
2. `vercel build` (no `--prod` flag).
3. `url=$(vercel deploy --prebuilt)` — preview deployment, captures URL like `https://peep-bot-abc123-tyler-cash.vercel.app`.
4. `vercel alias set "$url" event-staging.tylercash.dev`.
5. Exports `deployment_url` as a job output.

`deploy-prod-frontend`:

1. `vercel promote "$deployment_url"` — flips the same deployment to production, which auto-aliases to `event.tylercash.dev`. No rebuild, no new deployment ID.

## Failure handling

Fix-forward only, no auto-rollback.

| Failure | Behavior |
|---|---|
| Staging backend deploy fails | Pipeline stops. Staging stuck on broken version. Recovery: land fix on `main`. |
| Staging frontend deploy fails | Pipeline stops. Staging backend on new version, frontend on old — split-brain in staging is acceptable. |
| Manual gate denied / never approved | Pipeline ends successfully-but-skipped. Prod untouched. |
| Prod backend deploys, prod frontend fails | Prod is split-brain: new backend, old frontend. Acceptable because backend deploys first specifically to keep the API surface a superset of what the old frontend uses. Recover via re-run. |

## Prerequisites (one-time setup)

These must be in place before the new pipeline can run end-to-end. The current pipeline failure is item 1.

1. **`PORTAINER_DEPLOY_TOKEN` repo secret** on `Tyler-Cash/peep-bot`. Fine-grained PAT (or GitHub App installation token) scoped to `Tyler-Cash/portainer` with:
   - `Contents: write` (to commit compose changes)
   - `Actions: read` (so `gh run watch` can poll the portainer deploy workflow)
2. **GitHub environment `production`** on this repo (Settings → Environments → New). Configure: required reviewer = `Tyler-Cash`. No branch restriction needed (the job-level `if` already handles that).
3. **Vercel domain `event-staging.tylercash.dev`** added to the existing peep-bot Vercel project (dashboard or `vercel domains add`). DNS: `CNAME event-staging → cname.vercel-dns.com`.
4. **Portainer repo `Tyler-Cash/portainer`**: confirm `stacks/peepbot/docker-compose.yml` exists and that the repo's `deploy.yml` workflow path filter covers `stacks/peepbot/**` so prod commits actually trigger a deploy (not just `peepbot-staging`).

## Workflow file changes (summary)

- Rename existing `deploy-staging-backend` (already exists, structurally fine — we keep it but add `image_ref` job output).
- Add `deploy-staging-frontend` job. Replaces the current `frontend-deploy` job's behavior except it deploys to staging alias instead of `--prod`. Depends on `deploy-staging-backend`.
- Add `deploy-prod-backend` job. Mirrors `deploy-staging-backend` but writes to `stacks/peepbot/docker-compose.yml`, takes `image_ref` from staging job output, and declares `environment: production` (triggers manual gate).
- Add `deploy-prod-frontend` job. Runs `vercel promote` against the staging deployment URL. Depends on `deploy-prod-backend`. Also declares `environment: production` (the approval cascades — first job approval covers both since they share the environment, but explicit is fine).
- Remove the existing `frontend-deploy` job (replaced by the staging+prod pair).

## Out of scope, candidates for follow-up

- Automated smoke checks on staging (curl `/api/actuator/health` and a frontend route after each deploy).
- PR-triggered Vercel previews.
- Slack/Discord notification when manual gate is waiting.
- Capturing a "previous prod digest" output so a manual rollback PR is one click.
