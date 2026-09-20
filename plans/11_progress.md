# Task 11 progress

Working through `plans/11.md` (Google OIDC security + protected document access).

Baseline before changes: 513 tests green (`./mvnw clean verify`), HEAD `48fb9a8`.

Task 11 was first implemented and committed as `30ee2b8` (44 files), then found
**not complete** against a corrective review. This file records both the original
pass and the corrective pass.

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
  - A wrong session (stale cookie value) → `CSRF_SESSION_COOKIES_MISMATCH`.
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

Status: all corrective verifications green. Committed on top of `e59e9d4` (no
amend); ready for the review loop.

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
