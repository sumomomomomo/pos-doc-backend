# Task 11 progress

Working through `plans/11.md` (Google OIDC security + protected document access).

Baseline before changes: 513 tests green (`./mvnw clean verify`), HEAD `48fb9a8`.

## Verification log
(Commands and honest results recorded here as they run.)

- [x] `./mvnw -ntp test-compile` — BUILD SUCCESS (main + test compile, all new security/content classes).
- [x] `./mvnw -B -ntp clean verify` (run 1) — **605 tests, 0 failures, 0 errors, BUILD SUCCESS** (~59s).
- [x] `docker compose --env-file <stack-test env> config --quiet` — COMPOSE CONFIG OK (security vars wired; `SPRING_PROFILES_ACTIVE` passthrough).
- [x] `sh -n scripts/verify-container-stack.sh` — SYNTAX OK (stack-test mode, generated 64-hex bearer token, auth header on all API calls, pre-happy-path 401 check).
- [x] `scripts/verify-container-stack.sh` — **ALL CHECKS PASSED** (stack-test mode; backend health UP; 401 AUTHENTICATION_REQUIRED without the token; full ingestion + Task 10 smoke flow green with the bearer token). One fix during the run: the viewer/reviewer dummy subjects must be distinct (the validator rejects a subject in both lists), and the 401-body curl must capture stdout (not `/dev/null`).
- [x] secret scan of tracked content — no real credentials/tokens in the diff (only env-var references, `change-me`/`your-`/`test-`/`stack-test` placeholders, and per-run generated tokens); `target/` and `.env` remain gitignored.

Final: committed `30ee2b8` (44 files), pushed to `origin/main` (`48fb9a8..30ee2b8`).

## Steps
1. [x] Inspect repo; record generated signatures + MinIO API
2. [x] Add managed security deps + security-test
3. [x] OpenAPI: cookieAuth, /auth/me, /auth/logout, PDF/ZIP content, 503 response
4. [x] Generate + compile; record generated binary signature
5. [x] SecurityProperties + validation + tests (`SecurityPropertiesTest`, 25)
6. [x] OidcSubjectAuthorizer (allowlist/roles) + tests (`OidcSubjectAuthorizerTest`, 10) — fixed `getIssuer()` returns `URL` (compare via `.toString()`)
7. [x] SecurityConfiguration (filter chain, sessions, CSRF, CORS) + MVC tests (`GoogleSecurityFilterChainTest`, 29)
8. [x] CurrentUploaderProvider rewrite + upload tests (`CurrentUploaderProviderTest`, 9)
9. [x] CurrentUserService + AuthController + /auth tests (`AuthenticationControllerTest`, 5)
10. [x] DocumentContentService (descriptor + MinIO streaming outside tx) + tests (`DocumentContentServiceTest`, 8)
11. [x] Stack-test auth mode (profile + 64-hex token, constant-time) + tests
12. [x] compose.yaml, .env.example, README, verify-container-stack.sh
13. [x] Run all verifications, commit intended files

## Notes / deviations
- **Boot 4 removed `@MockBean`/`@SpyBean`**: the filter-chain test uses `org.springframework.test.context.bean.override.mockito.MockitoBean`; the content test builds a manual Mockito `spy(...)` to observe the MinIO call outside a transaction.
- **Spring Security 7 `AntPathRequestMatcher` is gone**: authorization uses a custom `AuthorizationManager<RequestAuthorizationContext>` with an `appPath()` helper (`getRequestURI()` minus context path) so it matches in both the real container and MockMvc (whose `getServletPath()` is empty).
- **CSRF matcher**: `CsrfFilter.DEFAULT_CSRF_MATCHER` requires CSRF for non-safe methods; `POST /pos-records/search` is the sole exempt read-only POST.
- **`/auth/me` materializes the CSRF cookie** by resolving the deferred token and calling `CookieCsrfTokenRepository.saveToken(token, request, response)` (the bean is shared between the filter chain and the controller).
- **Logout (POST /auth/logout)** is a POST so it requires CSRF in google mode; the Spring Security logout filter runs before authorization, so an unauthenticated logout is a safe 204 no-op.
- **Content streaming** happens outside the read-only descriptor transaction (verified by a spy asserting no active transaction during the MinIO `get`); the MinIO stream is closed via try-with-resources.
