# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Common commands

The dev workflow goes through Biff's task runner (`com.biffweb.tasks`) wired up via the `:dev` alias.

- `clj -M:dev dev` — start the dev server (HTTP on 8080, nREPL on the port set by `NREPL_PORT`, file-watch hot reload). Sets `BIFF_PROFILE=dev`.
- `clj -M:dev help` — list every available Biff task with docstrings (`uberjar`, `deploy`, `clean`, `format-code`, etc.).
- `clj -M:dev hello` — runs the example custom task in `dev/tasks.clj`; add new tasks by extending the `custom-tasks` map there (it gets merged with `tasks/tasks`).
- `clj -M:prod` — production entry point (`-m euporious`, `BIFF_PROFILE=prod`).
- In the REPL, `(euporious/refresh)` reloads modules without a server restart.
- Tests live in `test/euporious_test.clj` and run via the Biff task runner (`clj -M:dev test`).
- Frontend tooling: Tailwind CSS v4 + DaisyUI. `pnpm` is the package manager (`flake.nix` exposes both `tailwindcss` and `pnpm` via the direnv-activated devShell).

## Architecture

This is a **Biff** (Jacob O'Bryant's Clojure web framework) app: Ring + Reitit + XTDB 2.x + Rum + HTMX. Snapshot XTDB deps require the `sonatype-snapshots` Maven repo (already configured in `deps.edn`).

### Entry point and module composition

`src/euporious.clj` is the main namespace. It assembles a `modules` vector from the per-feature namespaces under `src/euporious/`. Each module follows Biff's convention of returning a map with `:routes`, `:schema`, `:tasks`, etc., which Biff merges into the running system.

### Multi-domain host-based routing

One process serves several sites, chosen by **exact** `Host` match (port stripped, case-insensitive). See `MULTI_DOMAIN_SETUP.md` for how to add a site.

- Each site is a map (`:id`, `:hosts`, `:dev-hosts`, `:modules`, optional `:auth {:emails #{…}}` and `:headers`) defined next to its feature code (`tv-archiv/site`, `secrets/site`, `decap-sites/site`) and listed in `euporious/sites`. `src/euporious/sites.clj` derives everything from that list: one Reitit handler per site, a per-site Biff auth module with its own email allowlist, and the global `modules` list (schema/tasks/queues/:on-tx only; routes are never served from it).
- Unknown hosts get a plain 404 — there is no fallback site. `:dev-hosts` (`tv.localhost`, `localhost`, `ots.localhost`, `decap.localhost`) are only honoured when `:euporious/dev-hosts?` is true (`#profile`, dev only).
- `:biff/base-url` is set per request from the registered host, so sign-in links and the OTS share links point at the site the user is on. (`DOMAIN` is unset in prod; before this, Biff's magic links would have pointed at `https:///…`.)
- Sessions are tagged with the site id at login (`mid/wrap-site-session`); a session whose `:site` differs from the current site is treated as signed out. Sessions from before this check have no tag, so everyone signs in once more after the deploy.
- **poleshook.com** (Decap CMS OAuth) reaches the app through `oksana.nix`, which proxies `/api/oauth/` there with `Host: poleshook.com` — that is what the `:decap` site is for. It used to work only through the old "unknown host → tv-archiv" fallback.
- `wrap-resource` (CSS/JS and the exported `:static` pages such as `/about/`) still runs before site dispatch, so those files are reachable on every registered host *and* on unknown ones.

### Feature modules (under `src/euporious/`)

- `home.clj` — landing/signup pages (German UI), root route varies by site.
- `tv_archiv.clj` plus the `tv_archiv/` directory — movie/TV archive UI with Malli query-param schemas; consumes TMDB-enriched metadata.
- `tmdb.clj` — TMDB API client used by the Clojure side.
- `secrets.clj` — one-time-secrets (OTS) service. Storage is an in-memory atom with expiry and single-view semantics, **not** XTDB. Secrets are text or files (≤ 20 MiB): multipart uploads use ring's `byte-array-store` (configured in `middleware.clj`'s `wrap-site-defaults`) so file contents never touch disk; revealing a file secret responds with a `Content-Disposition: attachment` download. A chime task (`purge-expired!`, declared via the module's `:tasks`) sweeps expired entries every 10 min — chime tasks and the middleware chain are captured at system start, so changes there need `(euporious/refresh)`, not just a re-eval. **Prod gotcha:** the reverse proxy needs `client_max_body_size 21m;` (set on the `ots.olivermotz.net` vhost in the NixOS server config, see Deployment below), otherwise nginx rejects uploads before they reach the app.
- `app.clj` — authenticated user area, including a WebSocket chat.
- `decap_sites.clj` — GitHub OAuth backend so Decap CMS can authenticate against per-site OAuth credentials.
- `email.clj` — transactional email via MailerSend, used for the email-link auth flow.
- `schema.clj` — Malli schemas (users, messages, etc.).
- `legal.clj`, `ui.clj`, `worker.clj`, `settings.clj`, `calendar/` — supporting modules.

The admin email is `settings/admin-email`; sites restrict sign-in via their `:auth :emails` allowlist.

### Storage

XTDB 2.x is the primary database. Dev uses local storage; prod can be configured for S3 (DigitalOcean Spaces) via `XTDB_STORAGE_*` env vars.

#### XTDB snapshot pin

`com.xtdb/xtdb-{api,aws,core}` are pinned in `deps.edn` to the **dated** snapshot `2.x-20260713.123245-1`, not the floating `2.x-SNAPSHOT`. A floating snapshot means every deploy silently re-resolves to whatever XTDB last published — on 2026-07-30 that jumped ~6 months of snapshots at once and took prod down with a 502:

- the newer snapshot added a `msgpack-core` dependency, and the first `clj -M:dev uberjar` after the version jump died with `ClassNotFoundException: org.msgpack.core.MessageInsufficientBufferException` loading `xtdb/serde.clj`. Re-running dependency resolution (`clj -A:dev -Spath`) fixed it, so the stale classpath cache written during the deploy was the culprit, not the jar — but it left no `target/jar/app.jar`, and the redeploy script started the service anyway.
- the newer snapshot also pulls `info.sunng/ring-jetty9-adapter`, whose **jetty-server 12.1.7** supersedes the **12.1.0** that Biff's `ring/ring-jetty-adapter 1.15.1` depends on. The `jetty-ee9-*` shim jars stay at 12.1.0, so *every* request logged `NoSuchFieldError: Class org.eclipse.jetty.server.HttpStream does not have member field 'java.lang.Exception CONTENT_NOT_CONSUMED'` and got aborted at completion (pages still rendered — the abort happens after the body is written — so this is easy to miss). Hence the `:exclusions [info.sunng/ring-jetty9-adapter]` on `xtdb-core`: the app runs Biff's own Jetty, never XTDB's HTTP server module.

When bumping XTDB, bump to another dated snapshot and check `clj -A:dev -Stree | grep jetty` stays on a single 12.1.x line.

### Configuration

`config.env` (gitignored) holds runtime config: `DOMAIN`, `TV_ARCHIV_DOMAIN`, `SECRETS_DOMAIN`, `TMDB_API_KEY`, `MAILERSEND_API_KEY`, `MAILERSEND_FROM`, `RECAPTCHA_SITE_KEY`, `RECAPTCHA_SECRET_KEY`, `COOKIE_SECRET`, `JWT_SECRET`, `NREPL_PORT`, and the optional `XTDB_STORAGE_*` set.

## Deployment

Production is **not** deployed the standard Biff way (`server-setup.sh` / `clj -M:dev deploy` are vestigial — don't touch them for prod changes). The app runs on the NixOS server **netcup-vps-2** (aarch64), whose flake config lives at `/media/lapdaten/ARBEITSSD/dev/servers/vps2/netcup-vps-2` (on the box: `/etc/nixos`, a checkout tracking `origin/main`).

- `euporious_public.nix` in that repo defines everything: nginx vhosts `ots.olivermotz.net` and `tobys-archiv.de` (ACME/SSL, gzip, `client_max_body_size`) both proxying to `127.0.0.1:5000`, plus the `euporious_public` systemd service running the uberjar from `/home/phylax/projects/euporious_public/target/jar/app.jar` with `BIFF_PROFILE=prod`.
- Note the prod port is **5000**, not the dev 8080.
- nginx/system changes ship via `nix run .#deploy` in the server repo — commit **and push** first; the deploy hard-resets the box to `origin/main` and rebuilds there.
- App code changes: run **`nix run .#deploy-prod`** from this repo. The flake app checks `main` against `origin/main` (offering a `--force-with-lease` push), runs `/etc/nixos/scripts/euporious_public_redeploy.sh` over `ssh vps-2`, then tags the deployed commit `deployed-<UTC timestamp>` and pushes the tag. The server script stops the service, hard-resets the box's checkout (`/home/phylax/projects/euporious_public/euporious_public`) to `origin/main`, builds the uberjar (`nix develop --command clj -M:dev uberjar`), and starts the service again — so there is downtime during the build, and the in-memory OTS secrets are lost on every redeploy. The script runs under `set -euo pipefail` and copies the current jar to `/home/phylax/projects/euporious_public/app.jar.prev` before building; a failed build is rolled back to that jar and the script exits non-zero (it used to ignore the build's exit code and restart the service against a jar the `clean` step had just deleted — that is the 502 in "XTDB snapshot pin" above).

## Python TMDB enrichment scripts

The top-level `*.py` scripts are a separate workflow (independent of the Clojure app) that enriches an org-mode movie file with TMDB metadata.

- `match_movies.py` — fuzzy-match org-mode titles to TMDB IDs using `rapidfuzz`; writes a CSV with confidence tiers (HIGH/MEDIUM/LOW) for review.
- `enrich_metadata.py` — read that CSV, fetch full TMDB details (year, runtime, director, cast, genres, countries, IMDB id, rating), and write them back as org properties. Skips entries already marked `:BACKFILLED:`. Rate-limited to ~4 req/s.
- `update_tmdb_from_api.py` / `update_tmdb_from_suggested.py` — populate `:TMDB_ID:` / `:TMDB_TITLE:` for entries flagged with `:SUGGESTED_SEARCH:`.
- Related helpers: `enrich_org_tmdb.py`, `match_movies_suggested.py`, `inject_knowledge_properties.py`, `apply_knowledge_batch.py`, `deduplicate_properties.py`.
- The TMDB API key is read from GNU `pass` (e.g. `pass tmdb/api-key`).
- This is NixOS, so install deps via nix-shell rather than pip:
  ```
  nix-shell -p 'python3.withPackages (ps: with ps; [ requests rapidfuzz ])' --run 'python3 enrich_metadata.py ...'
  ```

## Repo hygiene

- Editor backup files (`*~`, `#file#`) and the `.venv/` directory live in the working tree. Ignore them — do not read or edit them as if they were source.
- `target/resources` is on the classpath; built assets land there during dev/build.
