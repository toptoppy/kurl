# API

All endpoints use JSON except redirects. If `API_KEY` is non-empty, send `X-Api-Key` for `POST /api/shorten` and `GET /api/{slug}/stats`. Health and redirect endpoints are public.

## Create a short URL

`POST /api/shorten`

```json
{
  "url": "https://example.com/very/long/path",
  "custom_slug": "my-link"
}
```

`custom_slug` is optional. It must match `^[a-zA-Z0-9_-]{3,30}$`. Without it, KURL generates a six-character Base62 slug. `api`, `health`, `robots.txt`, and `favicon.ico` are reserved.

On success, returns `201 Created`:

```json
{
  "slug": "my-link",
  "short_url": "https://s.example.com/my-link",
  "original_url": "https://example.com/very/long/path"
}
```

Errors: `400 Bad Request` for invalid input, `401 Unauthorized` for a missing or incorrect configured key, `409 Conflict` when a custom slug is already used, and `503 Service Unavailable` if automatic slug allocation cannot find an available value.

## Redirect

`GET /{slug}` returns `302 Found` and a `Location` header for an existing slug. Each successful redirect increments the click counter. Unknown slugs return `404 Not Found`.

## Statistics

`GET /api/{slug}/stats`

Returns `200 OK`:

```json
{
  "slug": "my-link",
  "original_url": "https://example.com/very/long/path",
  "click_count": 42,
  "created_at": 1700000000
}
```

`created_at` is a Unix timestamp in seconds. Unknown slugs return `404 Not Found`.

## Health

`GET /health` runs `SELECT 1` against SQLite. A healthy service returns `200 OK` with `{"status":"UP","database":"OK"}`. A failed check returns `503 Service Unavailable` with `{"status":"DOWN","database":"ERROR"}`.
