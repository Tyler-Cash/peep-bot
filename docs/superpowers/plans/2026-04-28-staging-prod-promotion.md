# Staging → Production Promotion Pipeline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refactor `.github/workflows/ci.yml` so a single push to `main` deploys to staging, waits for a manual approval, then promotes the same artifacts to production for both backend (Portainer) and frontend (Vercel).

**Architecture:** Sequential staging deploy (backend → frontend) → GitHub environment-based manual approval gate → sequential prod deploy (backend → frontend). Backend prod reuses the exact `version@digest` resolved during staging. Frontend prod reuses the exact Vercel deployment URL produced during staging via `vercel promote`.

**Tech Stack:** GitHub Actions, Vercel CLI, Docker/GHCR, semantic-release, Portainer (deployed via cross-repo commit to `Tyler-Cash/portainer`).

**Spec:** `docs/superpowers/specs/2026-04-28-staging-prod-promotion-design.md`

---

## File Structure

Single file edit: `.github/workflows/ci.yml`.

- **Modify:** `deploy-staging-backend` — add `image_ref` job output.
- **Add:** `deploy-staging-frontend` — Vercel preview deploy + alias to `event-staging.tylercash.dev`. Replaces the old `frontend-deploy` job for staging.
- **Add:** `deploy-prod-backend` — commits prod compose using staging's `image_ref`. Declares `environment: production` (the manual gate).
- **Add:** `deploy-prod-frontend` — `vercel promote` of the staging deployment URL. Declares `environment: production`.
- **Remove:** `frontend-deploy` (replaced by the staging+prod pair).

No new files. No new docs (spec + commit messages cover it).

---

## Testing strategy

Workflow files can't be unit-tested. The validation loop is:

1. **Local syntax check** with `actionlint` (install via `go install github.com/rhysd/actionlint/cmd/actionlint@latest` or `brew install actionlint` if not present — the plan uses a docker fallback so no install is required).
2. **Branch push** — open a PR. PR-triggered jobs (`changes`, `backend-test`, `backend-e2etest`, `backend-build`, `backend-sbom`, `frontend-ci`, `frontend-sbom`) run and verify the file parses; deploy jobs are gated to `push` on `main` so they won't accidentally fire.
3. **Merge to `main`** — only after all prerequisites in Task 0 are confirmed in place. This is the real end-to-end test. Watch the run in the Actions tab.

---

## Task 0: Confirm prerequisites (no code)

These are external setup items required before this pipeline can run end-to-end. Do them before merging the workflow change to `main`. Each is independently verifiable.

- [ ] **Step 1: Create `PORTAINER_DEPLOY_TOKEN` repo secret**

  This is the secret that's currently unset and causing the failure. Create a fine-grained personal access token at https://github.com/settings/tokens?type=beta with:
  - **Resource owner:** `Tyler-Cash`
  - **Repository access:** Only select repositories → `Tyler-Cash/portainer`
  - **Permissions:** `Contents: Read and write`, `Actions: Read-only`, `Metadata: Read-only` (auto-required)

  Then add it on this repo: https://github.com/Tyler-Cash/peep-bot/settings/secrets/actions → New repository secret → name `PORTAINER_DEPLOY_TOKEN`, paste the token.

  Verify: `gh secret list --repo Tyler-Cash/peep-bot | grep PORTAINER_DEPLOY_TOKEN` should show it.

- [ ] **Step 2: Create `production` GitHub environment**

  Go to https://github.com/Tyler-Cash/peep-bot/settings/environments → New environment → name `production`. Configure:
  - **Required reviewers:** Add `Tyler-Cash` (yourself).
  - **Deployment branches:** Leave default (`No restriction` is fine — the job-level `if: github.ref == 'refs/heads/main'` already restricts it).
  - **Environment secrets:** none — the existing repo-level Vercel/Portainer secrets are inherited.

  Verify: `gh api repos/Tyler-Cash/peep-bot/environments | jq '.environments[].name'` should include `"production"`.

- [ ] **Step 3: Add `event-staging.tylercash.dev` to the Vercel project**

  Either via dashboard (Project → Settings → Domains → Add) or CLI:

  ```bash
  cd frontend
  vercel domains add event-staging.tylercash.dev
  ```

  Then create the DNS record at your registrar:

  ```
  CNAME event-staging.tylercash.dev → cname.vercel-dns.com
  ```

  Verify: `dig +short CNAME event-staging.tylercash.dev` returns `cname.vercel-dns.com.` (DNS propagation may take minutes).

- [ ] **Step 4: Confirm `peepbot/` prod stack and portainer path filter**

  In `Tyler-Cash/portainer`:
  - Confirm `stacks/peepbot/docker-compose.yml` exists. If it does not, the prod deploy will create it on first commit but Portainer also needs to be configured to manage that stack — out of scope for this plan, but flag it now.
  - Open `.github/workflows/deploy.yml` (or whatever the deploy workflow file is named in the portainer repo) and confirm its `paths` filter covers `stacks/peepbot/**` (not just `stacks/peepbot-staging/**`). If not, add it before merging this change. Otherwise the `gh run watch` step in `deploy-prod-backend` will hang and time out.

  Verify (run from anywhere):

  ```bash
  gh api repos/Tyler-Cash/portainer/contents/stacks/peepbot/docker-compose.yml --jq '.path'
  gh api repos/Tyler-Cash/portainer/contents/.github/workflows --jq '.[].name'
  ```

- [ ] **Step 5: Create a feature branch for the workflow changes**

  ```bash
  git checkout -b ci/staging-prod-promotion
  ```

  All subsequent code commits land on this branch. Open a PR to validate parsing before merge.

---

## Task 1: Add `image_ref` job output to `deploy-staging-backend`

**Files:**
- Modify: `.github/workflows/ci.yml` (the `deploy-staging-backend` job, currently lines 353–420)

The prod backend job needs to consume the exact `<version>@<digest>` string staging used. The current staging job already computes both values into env vars but doesn't expose them.

- [ ] **Step 1: Add `outputs` declaration and capture `image_ref` in the pull step**

  In `.github/workflows/ci.yml`, find the `deploy-staging-backend:` job header (currently around line 353). Add an `outputs:` block after the `permissions:` block, and update the "Pull new backend image and get digest" step to also write `image_ref` to `$GITHUB_OUTPUT`.

  The job header should look like this after the change:

  ```yaml
    deploy-staging-backend:
      needs: [release]
      if: github.ref == 'refs/heads/main' && github.event_name == 'push' && needs.release.outputs.new_release_published == 'true'
      runs-on: ubuntu-24.04
      permissions:
        contents: read
        packages: read
      outputs:
        image_ref: ${{ steps.pull.outputs.image_ref }}
      steps:
        - name: Set up lowercase owner
          run: echo "OWNER_LOWERCASE=$(echo "${{ github.repository_owner }}" | tr '[:upper:]' '[:lower:]')" >> $GITHUB_ENV

        - name: Login to GitHub Container Registry
          uses: docker/login-action@4907a6ddec9925e35a0a9e82d7399ccc52663121 # v4
          with:
            registry: ghcr.io
            username: ${{ github.actor }}
            password: ${{ secrets.GITHUB_TOKEN }}

        - name: Pull new backend image and get digest
          id: pull
          run: |
            VERSION="${{ needs.release.outputs.new_release_version }}"
            IMAGE="ghcr.io/${{ env.OWNER_LOWERCASE }}/peep-bot-backend:${VERSION}"
            docker pull "${IMAGE}"
            DIGEST=$(docker inspect --format='{{index .RepoDigests 0}}' "${IMAGE}" | cut -d@ -f2)
            echo "VERSION=${VERSION}" >> $GITHUB_ENV
            echo "DIGEST=${DIGEST}" >> $GITHUB_ENV
            echo "image_ref=${VERSION}@${DIGEST}" >> $GITHUB_OUTPUT
  ```

  Leave the rest of the job's steps (Checkout portainer repo, Update peepbot-staging backend image, Wait for portainer deploy) unchanged.

- [ ] **Step 2: Lint with actionlint**

  ```bash
  docker run --rm -v "$(pwd):/repo" --workdir /repo rhysd/actionlint:latest -color
  ```

  Expected: no errors. If `rhysd/actionlint` image isn't reachable, fall back to `npx --yes @action-validator/cli .github/workflows/ci.yml`.

- [ ] **Step 3: Commit**

  ```bash
  git add .github/workflows/ci.yml
  git commit -m "ci: expose image_ref output from deploy-staging-backend"
  ```

---

## Task 2: Replace `frontend-deploy` with `deploy-staging-frontend`

**Files:**
- Modify: `.github/workflows/ci.yml` (the `frontend-deploy` job, currently lines 422–479)

The current `frontend-deploy` job builds with `--prod` and deploys directly to production. We rewrite it as a staging-only job: build a preview, deploy it, alias to `event-staging.tylercash.dev`, and export the deployment URL for the prod job. The old PR comment step is dropped (PRs no longer deploy anywhere — out of scope per spec non-goals).

- [ ] **Step 1: Replace the `frontend-deploy` job with `deploy-staging-frontend`**

  Find the `frontend-deploy:` job (currently starts around line 422) and replace it with the following:

  ```yaml
    deploy-staging-frontend:
      needs: [release, deploy-staging-backend]
      if: github.ref == 'refs/heads/main' && github.event_name == 'push' && needs.release.outputs.new_release_published == 'true'
      runs-on: ubuntu-24.04
      permissions:
        contents: read
      env:
        VERCEL_TOKEN: ${{ secrets.VERCEL_TOKEN }}
        VERCEL_ORG_ID: ${{ secrets.VERCEL_ORG_ID }}
        VERCEL_PROJECT_ID: ${{ secrets.VERCEL_PROJECT_ID }}
      outputs:
        deployment_url: ${{ steps.deploy.outputs.deployment_url }}
      steps:
        - name: Checkout code
          uses: actions/checkout@de0fac2e4500dabe0009e67214ff5f5447ce83dd # v6

        - name: Set up Node.js
          uses: actions/setup-node@53b83947a5a98c8d113130e565377fae1a50d02f # v6
          with:
            node-version: 24
            cache: 'npm'
            cache-dependency-path: frontend/package-lock.json

        - name: Install Vercel CLI
          run: npm install --global vercel@latest

        - name: Pull Vercel environment (production settings)
          run: vercel pull --yes --environment=production --token=$VERCEL_TOKEN

        - name: Build with Vercel (preview build)
          run: vercel build --token=$VERCEL_TOKEN

        - name: Deploy preview to Vercel
          id: deploy
          run: |
            url=$(vercel deploy --prebuilt --token=$VERCEL_TOKEN)
            echo "deployment_url=$url" >> $GITHUB_OUTPUT
            echo "Staging deployment: $url"

        - name: Alias staging deployment to event-staging.tylercash.dev
          run: |
            vercel alias set "${{ steps.deploy.outputs.deployment_url }}" event-staging.tylercash.dev --token=$VERCEL_TOKEN
            echo "Staging now serving from: https://event-staging.tylercash.dev"
  ```

  Notes for the implementing engineer:
  - We `vercel pull --environment=production` because Vercel only has two environment scopes (`production` and `preview`); we want production env vars baked into the build since the same artifact will be promoted to prod. The `--prod` flag on `vercel build` and `vercel deploy` is what marks a deploy as production — *not* the env pull.
  - `vercel alias set <url> <domain>` requires that `event-staging.tylercash.dev` is already added to the project (Task 0 step 3).
  - Aliasing a non-prod deployment to a custom domain works on Hobby tier — domain assignments are not a paid feature.

- [ ] **Step 2: Lint with actionlint**

  ```bash
  docker run --rm -v "$(pwd):/repo" --workdir /repo rhysd/actionlint:latest -color
  ```

  Expected: no errors.

- [ ] **Step 3: Commit**

  ```bash
  git add .github/workflows/ci.yml
  git commit -m "ci: replace frontend-deploy with staging-only deploy-staging-frontend"
  ```

---

## Task 3: Add `deploy-prod-backend`

**Files:**
- Modify: `.github/workflows/ci.yml` (add new job after `deploy-staging-frontend`)

Mirror of `deploy-staging-backend` but writes to `stacks/peepbot/docker-compose.yml`, reuses staging's `image_ref` (no second `docker pull`), and declares `environment: production` to trigger the manual gate.

- [ ] **Step 1: Add `deploy-prod-backend` job**

  Insert the following job immediately after `deploy-staging-frontend`:

  ```yaml
    deploy-prod-backend:
      needs: [release, deploy-staging-backend, deploy-staging-frontend]
      if: github.ref == 'refs/heads/main' && github.event_name == 'push' && needs.release.outputs.new_release_published == 'true'
      runs-on: ubuntu-24.04
      environment: production
      permissions:
        contents: read
      steps:
        - name: Set up lowercase owner
          run: echo "OWNER_LOWERCASE=$(echo "${{ github.repository_owner }}" | tr '[:upper:]' '[:lower:]')" >> $GITHUB_ENV

        - name: Resolve image ref from staging
          run: |
            VERSION="${{ needs.release.outputs.new_release_version }}"
            IMAGE_REF="${{ needs.deploy-staging-backend.outputs.image_ref }}"
            DIGEST="${IMAGE_REF#*@}"
            echo "VERSION=${VERSION}" >> $GITHUB_ENV
            echo "DIGEST=${DIGEST}" >> $GITHUB_ENV
            echo "Promoting backend ${VERSION}@${DIGEST} (same digest as staging)"

        - name: Checkout portainer repo
          uses: actions/checkout@de0fac2e4500dabe0009e67214ff5f5447ce83dd # v6
          with:
            repository: Tyler-Cash/portainer
            token: ${{ secrets.PORTAINER_DEPLOY_TOKEN }}

        - name: Update peepbot prod backend image
          id: update-image
          run: |
            sed -i "s|image: ghcr.io/${OWNER_LOWERCASE}/peep-bot-backend:.*|image: ghcr.io/${OWNER_LOWERCASE}/peep-bot-backend:${VERSION}@${DIGEST}|" stacks/peepbot/docker-compose.yml
            git config user.email "github-actions[bot]@users.noreply.github.com"
            git config user.name "github-actions[bot]"
            git add stacks/peepbot/docker-compose.yml
            if git diff --staged --quiet; then
              echo "committed=false" >> $GITHUB_OUTPUT
            else
              git commit -m "chore(peepbot): promote backend to ${VERSION}"
              git push
              echo "committed=true" >> $GITHUB_OUTPUT
            fi

        - name: Wait for portainer deploy to complete
          if: steps.update-image.outputs.committed == 'true'
          timeout-minutes: 30
          run: |
            COMMIT_SHA=$(git rev-parse HEAD)
            echo "Waiting for portainer deploy workflow for commit ${COMMIT_SHA}..."
            for i in $(seq 1 18); do
              RUN_ID=$(gh run list --repo Tyler-Cash/portainer --workflow=deploy.yml --limit=10 --json databaseId,headSha --jq ".[] | select(.headSha == \"${COMMIT_SHA}\") | .databaseId" | head -1)
              if [ -n "${RUN_ID}" ]; then
                echo "Found run ${RUN_ID}, waiting for it to complete..."
                gh run watch "${RUN_ID}" --repo Tyler-Cash/portainer --exit-status
                exit $?
              fi
              echo "Run not found yet (attempt ${i}/18), retrying in 10s..."
              sleep 10
            done
            echo "Timed out waiting for portainer deploy workflow to appear"
            exit 1
          env:
            GH_TOKEN: ${{ secrets.PORTAINER_DEPLOY_TOKEN }}
  ```

  Notes:
  - `environment: production` is what triggers the GitHub manual approval gate. The first job in a run that references this environment is what waits — subsequent jobs reusing the same environment in the same run skip approval automatically.
  - We don't re-pull the image; we extract the digest from the staging output. The `git rev-parse HEAD` after the push gives us the new commit on the portainer repo for `gh run watch` to track.

- [ ] **Step 2: Lint with actionlint**

  ```bash
  docker run --rm -v "$(pwd):/repo" --workdir /repo rhysd/actionlint:latest -color
  ```

  Expected: no errors.

- [ ] **Step 3: Commit**

  ```bash
  git add .github/workflows/ci.yml
  git commit -m "ci: add deploy-prod-backend with manual gate via production env"
  ```

---

## Task 4: Add `deploy-prod-frontend`

**Files:**
- Modify: `.github/workflows/ci.yml` (add new job after `deploy-prod-backend`)

`vercel promote` against the staging deployment URL. Same artifact, flipped to prod. Backend prod must succeed first.

- [ ] **Step 1: Add `deploy-prod-frontend` job**

  Insert after `deploy-prod-backend`:

  ```yaml
    deploy-prod-frontend:
      needs: [deploy-staging-frontend, deploy-prod-backend]
      if: github.ref == 'refs/heads/main' && github.event_name == 'push'
      runs-on: ubuntu-24.04
      environment: production
      permissions:
        contents: read
      env:
        VERCEL_TOKEN: ${{ secrets.VERCEL_TOKEN }}
        VERCEL_ORG_ID: ${{ secrets.VERCEL_ORG_ID }}
        VERCEL_PROJECT_ID: ${{ secrets.VERCEL_PROJECT_ID }}
      steps:
        - name: Install Vercel CLI
          run: npm install --global vercel@latest

        - name: Promote staging deployment to production
          run: |
            STAGING_URL="${{ needs.deploy-staging-frontend.outputs.deployment_url }}"
            echo "Promoting ${STAGING_URL} to production..."
            vercel promote "${STAGING_URL}" --token=$VERCEL_TOKEN --yes
            echo "Production now serving from: https://event.tylercash.dev"
  ```

  Notes:
  - `vercel promote` flips the existing deployment to be the new production deployment without rebuilding. This auto-updates the production domain alias (`event.tylercash.dev`).
  - The `--yes` flag skips the interactive confirmation prompt.
  - We don't need `actions/checkout` here — `vercel promote` operates on a deployment URL, not local source.

- [ ] **Step 2: Lint with actionlint**

  ```bash
  docker run --rm -v "$(pwd):/repo" --workdir /repo rhysd/actionlint:latest -color
  ```

  Expected: no errors.

- [ ] **Step 3: Commit**

  ```bash
  git add .github/workflows/ci.yml
  git commit -m "ci: add deploy-prod-frontend via vercel promote"
  ```

---

## Task 5: Open PR to validate parsing

**Files:** none (push + PR)

The deploy jobs are gated to `push` on `main`, so they won't run from a PR. But every other job (`changes`, `backend-test`, `backend-e2etest`, `backend-build`, `backend-sbom`, `frontend-ci`, `frontend-sbom`) will run, and GitHub will validate the entire workflow file syntax. If there's a parse error or a reference to an undeclared output, GitHub flags it on the PR's checks tab.

- [ ] **Step 1: Push and open PR**

  ```bash
  git push -u origin ci/staging-prod-promotion
  gh pr create --title "ci: staging→prod promotion pipeline" --body "$(cat <<'EOF'
  ## Summary
  - Splits frontend deploy into staging (alias to `event-staging.tylercash.dev`) + prod (`vercel promote`)
  - Adds `deploy-prod-backend` reusing the staging backend image digest
  - Adds GitHub `production` environment gate between staging and prod
  - Spec: `docs/superpowers/specs/2026-04-28-staging-prod-promotion-design.md`

  ## Test plan
  - [ ] All non-deploy jobs pass on PR
  - [ ] Prerequisites (Task 0) confirmed complete before merge
  - [ ] After merge: watch full pipeline run end-to-end on Actions tab
  - [ ] Verify staging serves from event-staging.tylercash.dev with new version
  - [ ] Approve manual gate
  - [ ] Verify prod serves from event.tylercash.dev with same digest
  EOF
  )"
  ```

- [ ] **Step 2: Wait for PR checks**

  ```bash
  gh pr checks --watch
  ```

  Expected: all jobs pass except deploy jobs (which are skipped due to the `if` condition).

  If a deploy job's `outputs:` reference is invalid, GitHub flags it with a yellow annotation on the workflow file even though the job is skipped. Fix any flagged issues before proceeding.

---

## Task 6: Merge and observe first end-to-end run

**Files:** none

Only proceed after all of Task 0 is confirmed complete. This is the actual integration test for the change.

- [ ] **Step 1: Verify Task 0 completion**

  Re-run the verifications from Task 0:

  ```bash
  gh secret list --repo Tyler-Cash/peep-bot | grep PORTAINER_DEPLOY_TOKEN
  gh api repos/Tyler-Cash/peep-bot/environments | jq '.environments[].name'
  dig +short CNAME event-staging.tylercash.dev
  gh api repos/Tyler-Cash/portainer/contents/stacks/peepbot/docker-compose.yml --jq '.path'
  ```

  All four must return non-empty / expected values. If any fail, complete that prerequisite first.

- [ ] **Step 2: Merge PR**

  ```bash
  gh pr merge --squash --delete-branch
  ```

- [ ] **Step 3: Watch the run**

  ```bash
  gh run watch
  ```

  Expected sequence (verify each):
  1. Test/build/SBOM jobs pass.
  2. `backend-push`, `release` complete.
  3. `deploy-staging-backend` commits to portainer repo, waits for portainer deploy to finish.
  4. `deploy-staging-frontend` deploys, aliases `event-staging.tylercash.dev`. Visit it in a browser — should serve the new version.
  5. `deploy-prod-backend` shows as "Waiting for review". Approve at the URL the GitHub UI provides.
  6. `deploy-prod-backend` commits to portainer repo (prod compose), waits for portainer.
  7. `deploy-prod-frontend` runs `vercel promote`. Visit `event.tylercash.dev` — same version as staging.

- [ ] **Step 4: Verify backend digest match**

  Confirm staging and prod portainer commits reference the same digest:

  ```bash
  gh api repos/Tyler-Cash/portainer/commits --jq '.[0:5] | .[] | {sha, message: .commit.message}'
  ```

  The two most recent peepbot commits should be `chore(peepbot-staging): update backend to <ver>` and `chore(peepbot): promote backend to <ver>` with the same `<ver>`. Inspect both compose files to confirm identical `@sha256:...` digests.

---

## Self-Review

**1. Spec coverage:**
- Pipeline shape (sequential, backend-then-frontend per env, manual gate) → Tasks 1–4.
- Backend artifact reuse (same `version@digest`) → Task 1 (output) + Task 3 (consume).
- Frontend artifact reuse (`vercel promote`) → Task 2 (output URL) + Task 4 (consume).
- Manual gate via `production` environment → Task 0 step 2 + Tasks 3, 4 declare `environment: production`.
- Failure handling (fix-forward only) → no rollback tasks; behavior is implicit in the dependency graph (failed staging job halts pipeline).
- Prerequisites (token, env, domain, prod stack) → Task 0.
- PR behavior unchanged → Task 5 verifies.

**2. Placeholder scan:** None. All steps contain concrete code/commands.

**3. Type/identifier consistency:**
- `image_ref` output name used consistently in Task 1 (declare) and Task 3 (consume via `needs.deploy-staging-backend.outputs.image_ref`). ✓
- `deployment_url` output name consistent across Task 2 (declare) and Task 4 (consume via `needs.deploy-staging-frontend.outputs.deployment_url`). ✓
- Job names consistent: `deploy-staging-backend`, `deploy-staging-frontend`, `deploy-prod-backend`, `deploy-prod-frontend`. ✓
- `environment: production` declared on Task 3 and Task 4 (both prod jobs share the gate; first triggers the approval). ✓
