# Task 11 progress

Working through `plans/11.md` (Google OIDC security + protected document access).

Baseline before changes: 513 tests green (`./mvnw clean verify`), HEAD `48fb9a8`.

Task 11 was first implemented and committed as `30ee2b8` (44 files), then found
**not complete** against a corrective review. This file records both the original
pass and the corrective pass. A second review of `423ee12` raised four further
findings, fixed here.

## Review round 2 — fixes to `423ee12`

A second review found four issues at `423ee12`. All are fixed and verified below.

### 1. (High) Protected-content authorization aligned with Spring MVC path matching
- `GoogleSecurityConfiguration` no longer classifies protected content with a raw
  `getRequestURI()` regex. It now uses Spring Security 7's
  `PathPatternRequestMatcher` (GET, `/pos-records/{posRecordId}/documents/{documentId}/content`
  and `/pos-records/{posRecordId}/source-archive/content`), which matches relative to
  the servlet context path and, like Spring MVC's own `PathPattern` routing, ignores
  matrix (semicolon) variables. The `AuthorizationManager` is now request-aware
  (`decide(request, authentication)`).
- New `ProtectedContentAuthorizationTest` proves the decision directly: a USER is
  denied and a REVIEWER is allowed for **both** content URLs carrying `;x=1` (and the
  plain paths, as a control). The decision is tested at the unit level because the
  container rejects a `;x=1` request at path-matching (400, then a denied `/error`
  dispatch) before it reaches the controller — verified on a real embedded server —
  so the unit test is the precise way to prove the authorization stays aligned with
  Spring's `PathPattern` behavior (a raw-URI regex would have downgraded `;x=1` to a
  plain `ROLE_USER` read).

### 2. (Medium) Removed a false progress-file claim
- `plans/11_progress.md` claimed a stale-session CSRF test emitting
  `CSRF_SESSION_COOKIES_MISMATCH`. No such test exists and `CsrfAccessDeniedHandler`
  only emits `CSRF_TOKEN_INVALID` (the Task 11 contract). The false claim was
  removed; no new error code was introduced.

### 3. (Medium) Logout now proves deletion of `POSDOCSESSION`
- MockMvc clears the framework-managed `XSRF-TOKEN` on logout but does not replicate
  the container's expiration of the renamed session cookie (verified: `POSDOCSESSION`
  is absent from a MockMvc logout response), so it cannot demonstrate this.
- The logout success handler now performs **explicit cookie cleanup**: it writes
  `Set-Cookie: POSDOCSESSION=; Max-Age=0; Path=…; Secure; SameSite=…` from the
  configured session-cookie properties (`ServerProperties`), so the renamed session
  cookie is deterministically expired (the server-side session is still invalidated
  by `SecurityContextLogoutHandler`).
- New `LogoutSessionCookieEmbeddedServerTest` (real embedded Tomcat via
  `RANDOM_PORT`, seeding an authenticated session with a test-only filter since a real
  Google login is not possible in a test) asserts the logout response carries
  `POSDOCSESSION` with an empty value and `Max-Age=0`.

### 4. (Low) Whole-stack PDF comparison is now one-to-one
- `scripts/verify-container-stack.sh` previously counted how many downloaded PDFs
  matched *an* fixture entry (`MATCH=2` would pass even if both were `first.pdf`). It
  now computes the sorted pair of actual hashes and the sorted pair of expected
  fixture hashes and requires them to be identical (verified the logic rejects two
  identical copies and accepts a distinct pair).

## Corrective pass (this work)

Seven corrective items were required. All are implemented and verified below.
The task is treated as **incomplete until every corrective verification passes**;
that gate is now satisfied (see the verification log at the bottom), so the task
is ready for the review loop. A new commit is made on top of `e59e9d4` (never amend).

### 1. Fix the SPA CSRF behavior
- `SecurityConfiguration.cookieCsrfTokenRepository()` now builds an explicitly
  configured `CookieCsrfTokenRepository`: cookie name `XSRF-TOKEN`, header
  `X-XSRF-TOKEN`, path `/`, `HttpOnly=false`, `Secure=true`, `SameSite=Lax` (applied
  via `setCookieCustomizer`, which runs after the default path/secure/httpOnly
  defaults so it wins).
- `GoogleSecurityConfiguration.securityFilterChain()` uses `csrf.spa()` (the
  supported SPA mechanism: emits the cookie and accepts the raw cookie value back
  in the `X-XSRF-TOKEN` header) with that repository injected.
- `AuthenticationController.me()` no longer hand-persists a possibly
  XOR-wrapped token. It forces the framework to materialize the (raw) deferred
  token via `((DeferredCsrfToken) token).get()` before returning the user.
- Logout does **not** hand-clear the cookie; Spring Security's CSRF logout
  integration removes it.
- New `SpaCsrfRoundTripTest` (fresh context, `@DirtiesContext(BEFORE_CLASS)` so its
  repository swap cannot leak into other tests) proves, with **no** `.with(csrf())`:
  - `GET /auth/me` emits `XSRF-TOKEN` (asserts name, `Secure`, `SameSite=Lax`, and
    not `HttpOnly`).
  - A subsequent `POST /auth/logout` that replays the **exact** cookie value in
    `X-XSRF-TOKEN` passes (204) — no double XOR, no 403.
  - A wrong token → `CSRF_TOKEN_INVALID` (403).
  - Logout invalidates the session (reuse → 401) and clears the XSRF cookie.
- Existing tests were not weakened: they still prove the authorization matrix,
  session behaviour, and CSRF rejection; the only change was removing a
  `cookie().exists` assertion from the shared-context suite (a pre-existing
  cross-test artifact of `.with(csrf())` swapping the filter's repository).

### 2. Fix the documented Google callback URI
- The callback is under the `/api/v1` context path. README, `.env.example`, and
  `plans/11.md` now show `http://localhost:18080/api/v1/login/oauth2/code/google`
  locally and `https://<public-host>/api/v1/login/oauth2/code/google` in
  production.
- New `googleCallbackUriIncludesTheConfiguredContextPath` builds the chain with
  `MockMvc` `.contextPath("/api/v1")` and asserts the generated authorize request
  carries `redirect_uri=.../api/v1/login/oauth2/code/google`.

### 3. Make placeholder credentials fail closed
- `SecurityStartupValidator.isUsable()` now rejects empty/blank **and**
  `your-`-prefixed values, so `GOOGLE_CLIENT_ID/SECRET` left as the `.env.example`
  placeholders are rejected.
- `SecurityProperties` rejects `your-`-prefixed subject entries, and enforces that
  the viewer/reviewer lists are not **both** empty (at least one allowed subject).
- `compose.yaml` makes each subject list optional (`:-`) so a reviewer-only or
  viewer-only deployment can leave the other empty; `.env.example` ships both lists
  empty with a comment.
- New tests: `SecurityStartupValidatorTest.usableValuesAreAccepted` (a usable,
  non-placeholder value is accepted) and
  `SecurityPropertiesTest.bothSubjectListsEmptyIsRejected` /
  `placeholderSubjectValuesAreRejected`; plus a `SecurityProperties` assertion that
  a `your-` subject fails.

### 4. Add the missing security tests
- CORS: `CorsConfigurationTest` (configured origin) asserts a positive preflight
  (`OPTIONS` → `Access-Control-Allow-Origin: http://allowed.example`,
  `Allow-Methods` includes the method, `Allow-Credentials: true`) and a
  negative preflight for an unconfigured origin (no `Access-Control-Allow-Origin`).
  `GoogleSecurityFilterChainTest` asserts the **empty** allowed-origins mode emits
  no CORS permission headers.
- Logout session reuse: covered in `SpaCsrfRoundTripTest` (reuse of a logged-out
  session → 401, not 200; the XSRF cookie is cleared).

### 5. Verify protected content end-to-end
- `GoogleSecurityFilterChainTest`: `pdfContentStreamsNonEmptyBodyWithRequiredHeaders`
  and `sourceArchiveContentStreamsNonEmptyBodyWithRequiredHeaders` stream real bytes
  through the controller + service and assert status, `Content-Type`,
  `Content-Disposition` (inline PDF / attachment ZIP), `Cache-Control: no-store`,
  `Pragma: no-cache`, `X-Content-Type-Options: nosniff`, and non-empty body.
- `DocumentContentServiceTest`: `sourceStreamIsClosedOnSuccess` and
  `sourceStreamIsClosedOnReadFailure` prove the MinIO stream is closed on both the
  success and read-failure paths (try-with-resources).
- `scripts/verify-container-stack.sh` now, against the live stack:
  - asserts the record detail's `uploadedBy` is exactly `stack-test:principal`;
  - extracts the two document ids (via JSON parsing, not a naive grep, because the
    nested `storageObject` also has an `id`);
  - downloads each PDF and the source ZIP through the authenticated HTTP content
    endpoints and proves the bytes are byte-for-byte the fixture entries / uploaded
    ZIP, with the correct `Content-Type`, `Content-Disposition`, `Cache-Control`,
    `Pragma`, `X-Content-Type-Options`, and `Content-Length`;
  - after soft delete, re-GETs the saved content URLs and asserts sanitized 404s
    (`DOCUMENT_NOT_FOUND` for the PDF, `POS_RECORD_NOT_FOUND` for the source ZIP).

### 6. Clean up repository documentation
- `plans/11.md` contained the entire plan twice; the duplicated second half was
  removed.
- This file was rewritten to record the corrective pass and honest results.
- No verification is claimed that the script/tests do not actually perform.

### 7. Verification results (see log below)
- Two clean consecutive `./mvnw clean verify` runs.
- `docker compose --env-file .env.example config --quiet`.
- `scripts/verify-container-stack.sh`.
- Secret scan of the diff.

## Verification log
(Commands and honest results recorded here as they run.)

Original pass (commit `30ee2b8`):
- [x] `./mvnw -B -ntp clean verify` — **605 tests, 0 failures, 0 errors, BUILD SUCCESS**.
- [x] `docker compose --env-file <stack-test env> config --quiet` — COMPOSE CONFIG OK.
- [x] `sh -n scripts/verify-container-stack.sh` — SYNTAX OK.
- [x] `scripts/verify-container-stack.sh` — ALL CHECKS PASSED (stack-test mode).

Corrective pass (this commit, on top of `e59e9d4`):
- [x] `sh -n scripts/verify-container-stack.sh` — SYNTAX OK.
- [x] `./mvnw -B -ntp clean verify` (run 1) — **622 tests, 0 failures, 0 errors, BUILD SUCCESS** (~68s).
- [x] `docker compose --env-file .env.example config --quiet` — COMPOSE CONFIG OK.
- [x] `scripts/verify-container-stack.sh` — **ALL CHECKS PASSED** (stack-test mode),
      including the new content checks: `uploadedBy == stack-test:principal`, both
      PDFs downloaded byte-for-byte equal to the fixture entries with the required
      headers, the source ZIP downloaded byte-for-byte identical to the uploaded
      ZIP, and post-delete sanitized 404s (`DOCUMENT_NOT_FOUND`,
      `POS_RECORD_NOT_FOUND`).
- [x] `./mvnw -B -ntp clean verify` (run 2) — **622 tests, 0 failures, 0 errors, BUILD SUCCESS**.
- [x] Secret scan of `git diff e59e9d4` — no real credentials/tokens; only env-var
      references, `your-`/`change-me`/`test-`/`stack-test` placeholders, and the
      fake test value `a-usable-secret`.

Review round 2 (this commit, on top of `423ee12`):
- [x] `sh -n scripts/verify-container-stack.sh` — SYNTAX OK; sorted-hash one-to-one
      logic verified to reject two identical copies and accept a distinct pair.
- [x] `./mvnw -B -ntp clean verify` (run 1) — **627 tests, 0 failures, 0 errors, BUILD SUCCESS**.
- [x] `docker compose --env-file .env.example config --quiet` — COMPOSE OK.
- [x] `scripts/verify-container-stack.sh` — **ALL CHECKS PASSED** (stack-test mode,
      bearer-token auth; incl. the new one-to-one PDF comparison and the protected
      HTTP content checks). The renamed session-cookie / logout behavior is proven by
      `LogoutSessionCookieEmbeddedServerTest`, not by this script, which uses a bearer
      token and never touches `POSDOCSESSION` / `XSRF-TOKEN` / `/auth/logout`.
- [x] `./mvnw -B -ntp clean verify` (run 2) — **627 tests, 0 failures, 0 errors, BUILD SUCCESS**.

Status: all corrective and review-round-2 verifications green. Committed on top of
`423ee12` (no amend); ready for the review loop.

## Review round 3 + final MVP integration

### Review round 3 (commit `e733c8f`)
Two low-severity corrections: (a) reworded the whole-stack entry above so it no
longer claims the script proves session-cookie behavior (it runs in stack-test mode
with a bearer token and never touches `POSDOCSESSION`/`XSRF-TOKEN`/`/auth/logout`),
and (b) extended `LogoutSessionCookieEmbeddedServerTest` to also assert the deletion
cookie's scope — `name=POSDOCSESSION`, `value=""`, `Max-Age=0`, `Path=/` — and that
its path/domain match the original session cookie's.

### Final MVP integration changes
Five deployment/integration changes to complete the MVP (the frontend
`sumomo-blog` API client already matched the contract, so no endpoint changes were
needed):

1. **Post-login destination → the frontend subpage.** The default post-login redirect
   is now `/pos/` everywhere: `application.yaml`, `compose.yaml`, `.env.example`, and
   the `SecurityProperties` Java fallback. `SecurityPropertiesTest` covers the new
   default (`blankRedirectDefaultsToFrontendSubpage`,
   `explicitFrontendSubpageRedirectAccepted`).
2. **Google OAuth production URL.** README and `.env.example` document the production
   authorized redirect URI `https://sumomo.horse/api/v1/login/oauth2/code/google` and
   the production `.env` (client id/secret + `sub`-based allowlists, never email).
3. **CORS stays disabled.** README + `.env.example` document that
   `APP_SECURITY_ALLOWED_ORIGINS` stays empty in production (same origin behind
   Nginx).
4. **Forwarded-header/OAuth integration test.** New `OauthForwardedHeaderTest`
   (real embedded container) sends `X-Forwarded-Host/Proto/Port` to
   `/api/v1/oauth2/authorization/google` and asserts the generated Google
   authorization request carries `redirect_uri=
   https://sumomo.horse/api/v1/login/oauth2/code/google` (plus a negative control
   asserting the internal `http://localhost:…` address without the headers). This
   protects the `server.forward-headers-strategy=framework` deployment assumption.
5. **Production network restrictions.** The base `compose.yaml` now publishes **no**
   host ports (production-safe). New `compose.dev.yaml` publishes the development
   ports on `127.0.0.1` only; new `compose.production.yaml` publishes only the backend
   on `192.168.1.35:18080` and pins `APP_SECURITY_MODE=google` with an empty
   stack-test token. MinIO (9000/9001) and RabbitMQ (5672/15672) are not published to
   the LAN; the OCR host must not be exposed through Nginx (frontend-repo concern).
   `scripts/verify-container-stack.sh` now applies the dev override.
   - Note: Docker Compose **merges** (does not replace) `ports` lists on override, so
     an override `ports: []` cannot remove the base ports; the ports were therefore
     removed from the base file and re-added per-environment (the approach the task
     anticipated).

### Verification
- [x] `./mvnw -B -ntp clean verify` (run 1) — **630 tests, 0 failures, 0 errors, BUILD SUCCESS**.
- [x] `docker compose --env-file .env.example config --quiet` (base: 0 host ports) — OK.
- [x] `docker compose -f compose.yaml -f compose.dev.yaml --env-file .env.example config --quiet` — OK
      (5 ports, all `127.0.0.1`).
- [x] `docker compose -f compose.yaml -f compose.production.yaml --env-file .env.example config --quiet` — OK
      (only `192.168.1.35:18080`; MinIO/RabbitMQ unpublished; google mode pinned).
- [x] `scripts/verify-container-stack.sh` — **ALL CHECKS PASSED** (now via the dev
      override).
- [x] `./mvnw -B -ntp clean verify` (run 2) — **630 tests, 0 failures, 0 errors, BUILD SUCCESS**.

Status: final MVP integration changes complete and verified; in a new PR (not yet
pushed) pending the manual end-to-end integration acceptance test, which is handled
separately.

## Notes / deviations
- **Boot 4 removed `@MockBean`/`@SpyBean`**: the filter-chain test uses
  `org.springframework.test.context.bean.override.mockito.MockitoBean`; the content
  test builds a manual Mockito `spy(...)` to observe the MinIO call outside a
  transaction.
- **Spring Security 7 `AntPathRequestMatcher` is gone**: authorization uses a custom
  `AuthorizationManager<RequestAuthorizationContext>` with an `appPath()` helper
  (`getRequestURI()` minus context path) so it matches in both the real container
  and MockMvc (whose `getServletPath()` is empty).
- **CSRF (SPA)**: `csrf.spa()` accepts the raw `XSRF-TOKEN` cookie value back in the
  `X-XSRF-TOKEN` header (it only applies the legacy XOR when no header is present and
  a `_csrf` parameter is, which keeps `.with(csrf())` working). The cookie is
  `HttpOnly=false` so JS can read it; `Secure`/`SameSite=Lax` are set explicitly
  (Lax, not Strict, so the cross-site Google callback and top-level navigation
  still carry the session cookie).
- **`.with(csrf())` leaks the repository**: it reflectively swaps the `CsrfFilter`'s
  `CookieCsrfTokenRepository`, which permanently replaces the framework's default
  `XSRF-TOKEN` cookie with a `JSESSIONID`-named one for the rest of the shared
  context. `SpaCsrfRoundTripTest` therefore runs in its own
  `@DirtiesContext(BEFORE_CLASS)` context and never uses `.with(csrf())`.
- **`/auth/me` materializes the CSRF cookie** by forcing the framework's deferred
  token to load (`DeferredCsrfToken.get()`), which invokes the repository's
  `saveToken` and emits the raw cookie — no manual token manipulation.
- **Content streaming** happens outside the read-only descriptor transaction
  (verified by a spy asserting no active transaction during the MinIO `get`); the
  MinIO stream is closed via try-with-resources (asserted on success and failure).
- **Stack-test content check extracts document ids by JSON parsing**, not `grep`,
  because each document's nested `storageObject` also has an `id` field.
