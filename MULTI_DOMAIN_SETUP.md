# Multi-Domain Setup Guide

This application now supports serving two separate sites (TV Archiv and OTS) under different domains.

## Architecture

The app uses **host-based routing** where a single server process routes requests based on the `Host` header:
- Middleware (`wrap-site-context`) determines the site from the domain
- Routes are filtered per site while maintaining backward compatibility
- Each domain shows different content at the root `/` path

## Development Setup

### 1. Configure /etc/hosts

Add these entries to your `/etc/hosts` file:

```bash
sudo nano /etc/hosts
```

Add:
```
127.0.0.1 tv.localhost
127.0.0.1 ots.localhost
```

### 2. Start the Development Server

```bash
clj -M:dev dev
```

### 3. Access the Sites

- **TV Archiv**: http://tv.localhost:8080
- **Secrets (OTS)**: http://ots.localhost:8080
- **Original/Shared**: http://localhost:8080

## How It Works

### Site Detection

The middleware checks the `Host` header to determine which site to serve:

```clojure
(defn determine-site [host]
  (cond
    (str/starts-with? host "tv.") :tv-archiv
    (str/starts-with? host "ots.") :secrets
    :else :shared))
```

### Home Page Routing

The home page (`/`) shows different content based on the domain:
- `tv.localhost:8080/` → TV Archiv landing page
- `ots.localhost:8080/` → Secrets landing page
- `localhost:8080/` → Original signup page

### Site-Specific Routes

Each site has access to its own modules:

**TV Archiv domain** (`tv.localhost:8080`):
- `/` - Landing page
- `/tv-archiv` - Movie list (backward compatible)
- `/tv-archiv/list` - Filtered list endpoint
- Legal pages

**Secrets domain** (`ots.localhost:8080`):
- `/` - Landing page
- `/ots/new` - Create secret (requires auth)
- `/ots/retrieve/:uuid` - Retrieve secret
- `/ots/reveal/:uuid` - Reveal secret
- `/app/*` - Admin pages (requires auth)
- Legal pages

## Production Setup

### Environment Variables

Add these to your production environment:

```bash
TV_ARCHIV_DOMAIN=tv.yourdomain.com
SECRETS_DOMAIN=ots.yourdomain.com
DOMAIN=yourdomain.com  # Original/fallback domain
```

### DNS Configuration

Point both domains to your server:

```
A    tv.yourdomain.com    → your-server-ip
A    ots.yourdomain.com   → your-server-ip
```

### Reverse Proxy (Optional)

If using Nginx or Caddy, both domains should point to the same backend:

**Nginx example:**
```nginx
server {
    server_name tv.yourdomain.com ots.yourdomain.com;

    location / {
        proxy_pass http://localhost:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }
}
```

**Caddy example:**
```
tv.yourdomain.com, ots.yourdomain.com {
    reverse_proxy localhost:8080
}
```

The app automatically detects which domain was used via the `Host` header.

## Backward Compatibility

All existing routes continue to work on any domain:
- `/tv-archiv` works on all domains
- `/ots/*` works on all domains
- This ensures existing bookmarks and links don't break

## Testing

Test each domain in your browser:

1. **TV Archiv**: http://tv.localhost:8080
   - Should show TV Archiv landing page at `/`
   - `/tv-archiv` should show movie list

2. **Secrets**: http://ots.localhost:8080
   - Should show Secrets landing page at `/`
   - `/ots/new` should show create secret form (requires login)

3. **Original**: http://localhost:8080
   - Should show original signup page

## Troubleshooting

### "Site not found" or wrong content

Check that:
1. `/etc/hosts` entries are correct
2. You're accessing the correct domain (with port in dev)
3. Server is running and bound to `0.0.0.0` (not just `localhost`)

### Port not included in development

The config defaults include the port for development:
- `tv.localhost:8080`
- `ots.localhost:8080`

Make sure to include `:8080` when testing locally.

### Changes not reflecting

Restart the development server or run:
```bash
(euporious/refresh)
```
in the REPL.
