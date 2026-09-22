# POS Document Backend

Spring Boot backend that ingests POS-archive ZIP uploads into durable storage
(MinIO), records the ingestion state in SQLite, and hands off processing work to
RabbitMQ through a transactional outbox.

## Local endpoints (development)

Local development publishes the following ports, all bound to `127.0.0.1` (loopback
only) via `compose.dev.yaml`:

| Service | Endpoint |
| --- | --- |
| Backend API | `http://localhost:18080/api/v1` |
| MinIO API | `http://localhost:9000` |
| MinIO console (local development only) | `http://localhost:9001` |
| RabbitMQ AMQP | `localhost:5672` |
| RabbitMQ management (local development only) | `http://localhost:15672` |

The base `compose.yaml` publishes **no** host ports (production-safe). The ports
above come from the development override `compose.dev.yaml`; production publishes
only the backend on the backend server's LAN interface (see
[Production deployment](#production-deployment)). Ports `9001` and `15672` are
**local-development management ports and must never be publicly or LAN exposed**.

## Starting the stack (local development)

Copy `.env.example` to `.env` and change the credentials before starting the stack:

```bat
copy .env.example .env
docker compose -f compose.yaml -f compose.dev.yaml --env-file .env up --build
```

Do not commit real credentials, and do not put them in example commands.

## Security (Google OIDC)

The backend is protected by Google OAuth2/OIDC login. Authentication is
**fail-closed**: in the default `google` mode the app refuses to start unless the
Google client credentials and the subject allowlists are configured.

Set these in `.env` (see `.env.example`):

| Variable | Required | Meaning |
| --- | --- | --- |
| `GOOGLE_CLIENT_ID` | yes | Google OAuth client id. |
| `GOOGLE_CLIENT_SECRET` | yes | Google OAuth client secret. |
| `APP_SECURITY_GOOGLE_VIEWER_SUBJECTS` | at least one of the two lists | Comma-separated Google `sub` values granted read access. May be empty for a reviewer-only deployment. |
| `APP_SECURITY_GOOGLE_REVIEWER_SUBJECTS` | at least one of the two lists | Comma-separated Google `sub` values granted read + verify/delete/content access. May be empty for a viewer-only deployment. |
| `APP_SECURITY_ALLOWED_ORIGINS` | no | Comma-separated exact browser origins allowed for CORS. **Leave empty in production**: the frontend and API are the same origin behind the Nginx reverse proxy, so no CORS is needed. Set origins only for a genuinely cross-origin deployment. |
| `APP_SECURITY_POST_LOGIN_REDIRECT` | no | Absolute path to redirect to after login (default `/pos/`, the POS application subpage). |
| `APP_SECURITY_MODE` | no | `google` (default) or `stack-test`. |

Register the redirect URI in the Google Cloud OAuth client (the backend serves the
OAuth2 callback under its `/api/v1` servlet context path, so the URI must include
`/api/v1`):

- Local development: `http://localhost:18080/api/v1/login/oauth2/code/google`
- Production: `https://sumomo.horse/api/v1/login/oauth2/code/google`

The Google `sub` claim is the stable identifier used for the allowlists (an
account's `sub` never changes) — **use the `sub` value, not the user's email
address**.

Startup validation is fail-closed: in the default `google` mode the app refuses to
start unless the client credentials and subject lists are present, non-empty (at
least one list), and not left as the `your-...` placeholders from `.env.example`.

Roles:

- **viewer** (`ROLE_USER`): list records, search, and read record detail.
- **reviewer** (`ROLE_USER` + `ROLE_REVIEWER`): everything a viewer can do, plus
  verify, delete, and open the document/source-archive content.

After login, `/auth/me` returns the current user and the API uses a session cookie
(`POSDOCSESSION`) plus an `XSRF-TOKEN` cookie for CSRF protection (state-changing
requests must send the matching `X-XSRF-TOKEN` header).

## Production deployment

The frontend (the `sumomo-blog` repository) and this backend are served from the
**same origin** in production. The browser calls `https://sumomo.horse/api/v1/...`
and Nginx proxies those requests internally to the backend at
`192.168.1.35:18080`, preserving `/api/v1` and forwarding the public host and HTTPS
scheme in the `X-Forwarded-*` headers. Because the frontend and API are the same
origin, **CORS stays disabled** (`APP_SECURITY_ALLOWED_ORIGINS` is empty).

The backend is configured with `server.forward-headers-strategy=framework`, so
Spring's `ForwardedHeaderFilter` rewrites the request's scheme/host/port from the
forwarded headers. This is what makes the Google OAuth2 authorization redirect use
the public callback `https://sumomo.horse/api/v1/login/oauth2/code/google` (not an
internal address) and what makes the session cookie `Secure`. This deployment
assumption is protected by `OauthForwardedHeaderTest`.

### Deploying

Use the production override, which publishes only the backend port on the backend
server's LAN interface and pins google mode (and clears the test profile):

```bash
docker compose -f compose.yaml -f compose.production.yaml --env-file .env up --build
```

Production `.env` (never commit it) is set like:

```env
APP_SECURITY_MODE=google
GOOGLE_CLIENT_ID=<production-client-id>
GOOGLE_CLIENT_SECRET=<production-client-secret>
APP_SECURITY_GOOGLE_VIEWER_SUBJECTS=<allowed-google-subjects>
APP_SECURITY_GOOGLE_REVIEWER_SUBJECTS=<subjects-allowed-to-upload-and-download>
APP_SECURITY_ALLOWED_ORIGINS=
APP_SECURITY_POST_LOGIN_REDIRECT=/pos/
```

(Use the Google `sub` values in the allowlists, not email addresses.)

### Network boundary

The base `compose.yaml` publishes **no** host ports. `compose.production.yaml`
re-adds only the one port the reverse proxy needs and pins the security mode:

- Backend `18080` is bound to `192.168.1.35` (the backend server's LAN interface) —
  never `0.0.0.0`. Binding to a specific interface stops it being exposed on the
  server's other local interfaces, but it does **not** by itself restrict *clients*:
  any LAN host that can reach `192.168.1.35:18080` can still connect. The host
  firewall must therefore additionally allow TCP `18080` only from the Nginx server
  (and trusted administration hosts). This is required because the backend trusts
  `X-Forwarded-*` headers, so an untrusted client that could reach it directly could
  forge them.
- MinIO (`9000`/`9001`) and RabbitMQ (`5672`/`15672`) are **not** published to the
  LAN; they are reachable only on the compose internal network.
- `stack-test` authentication is never enabled in production (the override pins
  `APP_SECURITY_MODE=google`, an empty stack-test token, and an empty
  `SPRING_PROFILES_ACTIVE`).
- The OCR service (`192.168.1.34:8080`) is an internal backend-to-backend
  dependency and must **not** be exposed through Nginx (enforced in the frontend
  repository's Nginx configuration).

## Ingestion behavior

- `POST /api/v1/pos-records` accepts a multipart ZIP (max 10 MiB compressed)
  containing PDF documents and returns `202` with the generated `posRecordId`
  and `jobId`.
- The original ZIP is stored **byte-for-byte** in MinIO under a generated
  UUID-only object key (`archives/{posRecordId}/{storageObjectId}.zip`). The
  original filename, eRef, and policy number are stored only as metadata and
  never appear in the object key or in the message payload.
- Storage metadata, the POS record, the ingestion job, and the outbox event are
  committed in a single SQLite transaction.

## Structured field extraction

Once a job is dequeued, the consumer extracts three business fields from the
record's candidate documents and applies the resolved values to the POS record.
This replaces the old "OCR every PDF" workflow:

- **Candidate selection** — candidates are chosen in sequence order:
  - Every PDF whose basename ends with `LAPPe.pdf` (**case-sensitive**) is a
    candidate.
  - If none match, the first up to 10 PDFs are the candidates.
  - Candidates are processed **sequentially**: for each candidate only the
    still-unresolved fields are requested, and the workflow stops as soon as
    every field resolves (a later candidate is used only when an earlier one
    leaves a field `UNKNOWN`).
- **Render once** — each candidate's first page is rendered to PNG a single time
  and reused across that candidate's fields.
- **Three structured calls** — one HTTP request per unresolved field (policyholder
  name, consultant name, submission date), each sent with its own exact prompt
  (prompt version 2). The calls are deterministic: temperature `0`, `max_tokens` `128`.
- **Bounded per-field retry** — each field gets up to 3 attempts (1 + 2 retries)
  with a short bounded back-off before a terminal outcome is recorded. Malformed,
  empty, or truncated model responses are retried within the field.
- **Durable outcomes** — each (document, field, prompt version) result is
  persisted to `pos_field_extraction` as `RESOLVED` (with the validated value),
  `UNKNOWN` (absent/unreadable), or `FAILED` (a stable, PII-free error code).
  Only the value stored in the winning (authoritative) upsert row is applied to
  the record. Values are canonicalized: a name must be a single line of letters,
  spaces, apostrophes, hyphens, and single-letter initials (so `A. K. Tan` is
  valid) — no word-count cap, but a known label/prose prefix (`Policyowner Name
  ...`, `The name is ...`) or a colon is rejected as prose while a legitimate
  surname that merely contains a label-like word (`Masamune Date`) is accepted —
  and the policyholder's trailing bracketed ID is stripped before validation (so
  `UNKNOWN (123)` is an unresolved token, never a name); the submission date
  `dd-MMM-yyyy` is validated and stored as ISO `yyyy-MM-dd`.
- **Render failure** — a permanent render failure for a candidate (corrupt,
  encrypted, or otherwise invalid PDF) marks that candidate `FAILED` and the
  workflow moves on to the next candidate (best-effort; the job still completes).
  A temporary storage/rendering failure, and any interruption, escape to the
  consumer's bounded retry / DLQ path (an interruption is never persisted as a
  `FAILED` outcome).
- **Idempotent redelivery** — on a redelivery the workflow reconciles each
  candidate's durable outcomes before rendering. A candidate whose decisions are
  all durable is preserved (`COMPLETED`) and is **not** re-rendered or re-requested;
  terminal states (`COMPLETED`/`FAILED`/`SKIPPED`) are never demoted. A business
  field is re-checked before each OCR request, so a concurrent human edit prevents
  the corresponding call.
- **Best-effort** — a field that is `UNKNOWN` or `FAILED` leaves the record's
  business field `NULL`; it does **not** fail the job. The record always ends
  `REVIEW_REQUIRED` (human review is still required).
- **Non-candidates are skipped** — documents that are not candidates (and
  candidates that become unnecessary once every field is resolved) are marked
  `SKIPPED` and are never rendered or sent to the OCR service.
- **Worst-case budget** — each field is bounded to 3 attempts; with a single
  candidate the worst case is 3 fields × 3 attempts × ~10 s per request ≈ **90 s**.

## Message queueing and outbox

- RabbitMQ carries **identifiers only** (event, job, and POS record IDs, a
  schema version, and a timestamp) — never archive bytes or PII.
- Publication is **at-least-once**: a crash after the broker confirms but before
  the outbox row is stamped published can cause the same event to be published
  again. Consumers must be idempotent on `eventId`/`jobId`.
- An event is marked published only after a **positive publisher confirm** and
  successful routing (unroutable mandatory returns are treated as failures).
- A broker outage does **not** invalidate an already accepted upload and does
  **not** mark the job failed; the outbox row simply stays unpublished with a
  bounded retry back-off.
- Jobs remain `QUEUED` until a future task adds the production consumer.

## Failure and recovery notes

- A crash between the MinIO upload and the SQLite commit can leave an orphan
  MinIO object; reconciliation is deferred to a later task.
- SQLite uses a Hikari pool size of `1`; the database unique indexes remain the
  final authority for eRef/policy uniqueness (repository `exists` checks are
  advisory only).

## Verification

```bat
mvnw.cmd clean verify
:: base config is production-safe (no host ports)
docker compose --env-file .env.example config --quiet
:: local-development and production overrides resolve as expected
docker compose -f compose.yaml -f compose.dev.yaml --env-file .env.example config --quiet
docker compose -f compose.yaml -f compose.production.yaml --env-file .env.example config --quiet
bash scripts/verify-container-stack.sh
```
