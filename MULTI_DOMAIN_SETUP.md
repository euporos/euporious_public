# Multi-domain setup

One euporious process serves several sites. The `Host` header picks the site;
the mechanism lives in `src/euporious/sites.clj`.

## Sites today

| id           | prod host            | dev host(s)                  | defined in                  |
|--------------|----------------------|------------------------------|-----------------------------|
| `:tv-archiv` | `tobys-archiv.de`    | `tv.localhost`, `localhost`  | `euporious.tv-archiv/site`  |
| `:secrets`   | `ots.olivermotz.net` | `ots.localhost`              | `euporious.secrets/site`    |
| `:decap`     | `poleshook.com`      | `decap.localhost`            | `euporious.decap-sites/site`|
| `:family`    | `family.olivermotz.net` | `family.localhost`        | `euporious.family/site`     |

Any other host gets a 404.

## Adding a site

1. **Feature namespace.** Write the module as usual (`:routes`, `:api-routes`,
   `:schema`, `:tasks`, …) and, next to it, the site map:

   ```clojure
   (def site
     {:id        :family
      :hosts     #{"family.olivermotz.net"}
      :dev-hosts #{"family.localhost"}
      :modules   [module]                       ; only these routes are served
      :auth      {:emails #{"a@example.com"}    ; optional: email sign-in,
                  :biff.auth/app-path "/"}      ;   other biff.auth options pass through
      :headers   {"X-Robots-Tag" "noindex"}})   ; optional: on every response
   ```

   More optional keys (see `euporious.sites`): `:name` (sender name in sign-in
   mails), `:email-templates` (own sign-in mails), `:on-error` (own 404/500
   pages), and in `:auth`: `:emails :some/config-key` (allowlist from a
   config.env value; needs a service restart when changed), `:recaptcha? false`, `:code-minutes 10`.
   `euporious.family/site` uses all of them.

   Nothing is shared implicitly: add `legal/module` (Impressum/Datenschutz,
   linked from `ui/footer`) or `home/module` (sign-in pages) if the site needs
   them.

2. **Register it** in `euporious/sites` (`src/euporious.clj`).

3. **Server** (`/media/lapdaten/ARBEITSSD/dev/servers/vps2/netcup-vps-2`):
   add the host to the `sites` list in `euporious_public.nix` (one entry =
   one ACME vhost proxying to the app; extra locations per entry), and make
   sure DNS points at vps-2. Deploy the server repo first, then the app.

## Development

`clj -M:dev dev`, then use a `Host` header or the dev host name directly:

```sh
curl -H 'Host: ots.localhost:8080' http://127.0.0.1:8080/
```

Browsers resolve `*.localhost` to 127.0.0.1 on their own, so
`http://ots.localhost:8080` usually works without `/etc/hosts` entries.

## Behaviour worth knowing

- Sign-in is per site. Only emails in the site's `:auth :emails` get a code or
  link, and the session is tagged with the site id; a session from another site
  counts as signed out.
- `:biff/base-url` is set per request from the (registered) host, so absolute
  links in emails point at the site the user is on.
- Static resources (`resources/public`, exported `:static` pages) are served
  before site dispatch and are therefore reachable on every host.
