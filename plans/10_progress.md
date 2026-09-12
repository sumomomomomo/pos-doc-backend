# Task 10 progress

Persistence-backed POS-record review and search API.

## Status: COMPLETE

## Log

### Reading / design
- Read AGENTS.md, plans/10.md, OpenAPI, controllers, entities, repositories,
  MetadataNormalizer, mappers, ApiExceptionHandler, V1 migration, pom.xml, and
  representative tests (PosRecordRepositoryIntegrationTest, ApiSkeletonTest,
  MetadataNormalizerTest, verify-container-stack.sh).
- Confirmed environment constraints that shape the design:
  - `spring.jpa.open-in-view: false` and Hikari `maximum-pool-size: 1`.
    => command operations use sequential `TransactionTemplate` transactions
       (never nested / REQUIRES_NEW), and lazy `sourceArchive` is mapped inside
       the transaction.
  - Xerial SQLite driver reports null SQL state, so constraint violations
    surface as `DataAccessException` (JpaSystemException /
    UncategorizedSQLException), NOT `DataIntegrityViolationException`.
    => duplicate handling catches `DataAccessException`, treats
       `OptimisticLockingFailureException` as 412, and otherwise re-checks
       normalized existence in a fresh transaction to pick the exact 409 code.

### Design decisions
- New package `horse.sumomo.pos_doc_backend.review` with:
  - `PosRecordApiException` (+ `Code` enum) — narrowly-scoped API errors.
  - `TrigramSimilarity` — deterministic Sørensen–Dice name similarity.
  - `PosRecordApiMapper` — entity -> PosRecord / PosRecordSummary / StorageObjectSummary.
  - `PosRecordReadService` (detail read), `IngestionJobReadService` (job read),
    `PosRecordSearchService` (search), `PosRecordCommandService` (patch/verify/delete).
  - `ReviewConfiguration` — provides a `Clock` bean for deterministic `updatedAt`.
- `PosRecordRepository` extended with `JpaSpecificationExecutor` for the
  dynamic active-record search filter (additive; no existing method removed).
- Controllers rewired to the real services; `DummyPosRecordService` and
  `DummyIngestionJobService` deleted (both become unused; plan step 10).

### OpenAPI
- Added `POST /pos-records/{posRecordId}/verification` (operationId
  `verifyPosRecord`) with body `VerifyPosRecordRequest { expectedVersion }`.
- Added `VerifyPosRecordRequest` schema (additionalProperties false, required
  expectedVersion int64 min 0).
- Clarified PATCH description (omitted == explicit null == unchanged; reviewable
  state requirement; COMPLETED edit returns to REVIEW_REQUIRED; 412 on version
  mismatch; 400 on empty patch).

### Verification
All required verification commands pass (two clean Maven runs, compose config,
and the whole-stack script run twice, each ending in `ALL CHECKS PASSED`):

- `./mvnw clean verify` (run twice): **504 tests, 0 failures, 0 errors**, BUILD
  SUCCESS (up from 431 before Task 10). New / rewritten coverage:
  - `TrigramSimilarityTest` (12) - deterministic Sorensen-Dice scoring.
  - `PosRecordReadServiceIntegrationTest` (5) - detail read mapping + 404.
  - `PosRecordSearchServiceIntegrationTest` (15) - exact/fuzzy matching,
    filters, sorting, pagination, deleted-record exclusion. Clears the DB after
    each test so broad (top-20) queries stay deterministic.
  - `PosRecordCommandServiceIntegrationTest` (21) - PATCH (editable fields,
    no-op, state, duplicates, 412, fixed-clock updatedAt), verification
    (prerequisites, 409, 412), soft delete (excluded from all public reads,
    eRef/policy reuse after delete).
  - `PosRecordCommandServiceConcurrencyTest` (6) - pure unit tests proving a
    flush-time `@Version` conflict maps to 412/409 and a flush-time
    `DataAccessException` resolves to the exact 409 code (or rethrows for the
    500 handler).
  - `ApiSkeletonTest` (rewritten, 30) - contract / error-code / validation
    tests against the new services.
- `docker compose --env-file .env.example config --quiet`: OK.
- `scripts/verify-container-stack.sh`: **ALL CHECKS PASSED** (twice).
  - The two Task 1 dummy-endpoint checks (steps 6 and 10) are now real
    `404 POS_RECORD_NOT_FOUND` checks.
  - Appended a synthetic-metadata Task 10 smoke flow on the ingested record:
    detail read (REVIEW_REQUIRED) -> PATCH (stores holder/consultant, bumps
    version) -> search (finds by policy number) -> verification (COMPLETED,
    bumps version) -> soft delete (204) -> deleted record hidden from detail
    and search.
  - Robustness: `http_call` runs curl as a plain statement with a
    statement-level `|| true` (HTTP status written to a temp file) so a
    transient no-response right after a cold backend start never trips
    `set -e`; `http_get_retry` polls the readiness-sensitive detail reads.
- No LAN OCR contact: the whole-stack run uses the test-only WireMock
  `ocr-stub:8080` (compose.test-ocr.yaml).
