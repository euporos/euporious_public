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

A single server process serves multiple sites by inspecting the `Host` header. See `MULTI_DOMAIN_SETUP.md` for the full setup guide.

- `src/euporious/middleware.clj` defines `determine-site`: hosts starting with `tv.` → `:tv-archiv`, hosts starting with `ots.` → `:secrets`, otherwise `:shared`.
- Site-specific Reitit routers are pre-compiled at startup for the `:tv-archiv` and `:secrets` sites; the wrapping middleware dispatches each request to the right one.
- For local development you must add `/etc/hosts` entries for `tv.localhost` and `ots.localhost`, then access `http://tv.localhost:8080` and `http://ots.localhost:8080`. `localhost:8080` itself serves the shared/legacy routes.
- All legacy routes (`/tv-archiv`, `/ots/*`) remain reachable on every domain — keep that backward compatibility when adding routes.
- Production sets `DOMAIN`, `TV_ARCHIV_DOMAIN`, and `SECRETS_DOMAIN`; both subdomains point at the same backend through the reverse proxy.

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

There is a hardcoded admin email used as an auth gate in the main namespace; grep before assuming a generic auth flow when reasoning about access control.

### Storage

XTDB 2.x is the primary database. Dev uses local storage; prod can be configured for S3 (DigitalOcean Spaces) via `XTDB_STORAGE_*` env vars.

### Configuration

`config.env` (gitignored) holds runtime config: `DOMAIN`, `TV_ARCHIV_DOMAIN`, `SECRETS_DOMAIN`, `TMDB_API_KEY`, `MAILERSEND_API_KEY`, `MAILERSEND_FROM`, `RECAPTCHA_SITE_KEY`, `RECAPTCHA_SECRET_KEY`, `COOKIE_SECRET`, `JWT_SECRET`, `NREPL_PORT`, and the optional `XTDB_STORAGE_*` set.

## Deployment

Production is **not** deployed the standard Biff way (`server-setup.sh` / `clj -M:dev deploy` are vestigial — don't touch them for prod changes). The app runs on the NixOS server **netcup-vps-2** (aarch64), whose flake config lives at `/media/lapdaten/ARBEITSSD/dev/servers/vps2/netcup-vps-2` (on the box: `/etc/nixos`, a checkout tracking `origin/main`).

- `euporious_public.nix` in that repo defines everything: nginx vhosts `ots.olivermotz.net` and `tobys-archiv.de` (ACME/SSL, gzip, `client_max_body_size`) both proxying to `127.0.0.1:5000`, plus the `euporious_public` systemd service running the uberjar from `/home/phylax/projects/euporious_public/target/jar/app.jar` with `BIFF_PROFILE=prod`.
- Note the prod port is **5000**, not the dev 8080.
- nginx/system changes ship via `nix run .#deploy` in the server repo — commit **and push** first; the deploy hard-resets the box to `origin/main` and rebuilds there.
- App code changes: push `main` here, then run `scripts/euporious_public_redeploy.sh` from the server repo **on the box** (`ssh vps-2 'bash /etc/nixos/scripts/euporious_public_redeploy.sh'`). It stops the service, hard-resets the box's checkout to `origin/main`, builds the uberjar (`nix develop --command clj -M:dev uberjar`), and starts the service again — so there is downtime during the build, and the in-memory OTS secrets are lost on every redeploy.

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
