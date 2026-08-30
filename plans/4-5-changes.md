# Tasks 4–5 — Sprint changes report

> **Revision history**
>
> 1. `45637d6` — initial implementation (ZIP intake + RabbitMQ outbox).
> 2. `1e3109d` — fixture helper.
> 3. `3d8e93e` — first corrective commit (review findings 1–7).
> 4. *this commit* — second corrective commit:
>    - **Finding 8**: enforce *all* ZIP expansion limits during each entry read
>      (pre-entry `effectiveEntryLimit` arithmetic with overflow-safe ratio
>      product; per-entry, total, and ratio cap all enforced *during* the
>      bounded read, not only after the entry finishes).
>    - **Finding 9**: the previous `UploadSizeRejectionIntegrationTest`
>      exercised a MockMvc-only path that bypassed the servlet container's
>      multipart parser, so it proved the application-level mapping but not
>      that `MaxUploadSizeExceededException` was handled. The test class
>      has been split into three layers:
>      - `MaxUploadSizeAdviceTest` — direct invocation of
>        `ApiExceptionHandler.handleMaxUploadSize` with a real
>        `MaxUploadSizeExceededException`, asserting `413` /
>        `application/problem+json` / `ARCHIVE_TOO_LARGE` / sanitized
>        detail.
>      - `UploadSizeRealHttpRejectionTest` — a real HTTP integration test
>        using `@SpringBootTest(webEnvironment = RANDOM_PORT)` that sends
>        a 10 MiB + 1 byte multipart request over a raw `HttpURLConnection`
>        to the embedded Tomcat. The container's multipart parser raises
>        `MaxUploadSizeExceededException` before the request reaches
>        `BoundedUploadSpooler`; the advice maps it to the 413 problem
>        response. The intake service is mocked and must never be invoked.
>        MinIO and RabbitMQ are not contacted.
>      - `ApiSkeletonTest.oversizeUploadReturns413ArchiveTooLarge` (already
>        present) keeps the MockMvc-based application-level 413 mapping
>        test.
>      The misleading comment that claimed MockMvc exercises servlet
>      multipart parsing has been removed.
>
> See §8.8 below for the per-finding diffs and the new test coverage.
>
> ## Verification (this commit)
>
> 1. `mvnw.cmd clean verify` → **Tests run: 151, Failures: 0, Errors: 0,
>    Skipped: 0** — BUILD SUCCESS (run twice, idempotent).
> 2. `docker compose --env-file .env.example config --quiet` → exit 0.
> 3. `bash scripts/verify-container-stack.sh` →
>    **`verify-container-stack: ALL CHECKS PASSED`** (full stack: MinIO,
>    RabbitMQ, backend; upload 202; job `QUEUED`; queue message has only
>    identifiers; durable message survives RabbitMQ restart; MinIO marker
>    survives restart; SQLite file present across restarts; backend
>    remains healthy across restarts).
> 4. `mvnw.cmd clean verify` (post-stack) → **Tests run: 151, Failures: 0,
>    Errors: 0, Skipped: 0** — BUILD SUCCESS.
> 5. `git status --short` → 5 paths (1 modified production file, 1 deleted
>    test file, 1 modified test file, 2 new test files).
>
> All Testcontainers-based integration tests (real MinIO, real RabbitMQ,
> real Spring context) executed with zero skips.
>
> ---

# Tasks 4–5 — Sprint changes report

Companion document to [4-5_zip_rabbitmq.md](4-5_zip_rabbitmq.md). This report
describes, end to end, what the sprint implementing Tasks 4–5 (secure ZIP
intake + reliable RabbitMQ enqueue) built, and the corrective follow-up that
addressed the static-review findings raised against the first two commits.

The work is delivered as two commits:

1. `45637d6` — *feat: ZIP ingestion with MinIO storage and RabbitMQ
   transactional outbox (task 4-5)* (plus the small `1e3109d` fixture helper).
2. This commit — *fix: address review findings on ZIP limit enforcement, error
   mapping, outbox batch limit, logging, and fixture hashing.*

---

## 1. What the sprint implements

The first real ingestion boundary, ending at the reliable queue:

```text
HTTP multipart upload
        |
        v
bounded temporary file + SHA-256            (BoundedUploadSpooler)
        |
        v
ZIP validation (one or more PDFs)          (ZipArchiveValidator)
        |
        v
MinIO source archive                        (MinioObjectStorage)
        |
        v
one SQLite transaction:                     (IntakeDatabaseService)
  storage_object + pos_record + ingestion_job + outbox_event
        |
        v
202 Accepted

separate scheduled relay:                   (OutboxRelay + RabbitOutboxPublisher)
  unpublished outbox_event -> RabbitMQ -> publisher confirm -> mark published
```

Key guarantees:

- MinIO durability precedes the database referring to the object.
- The four metadata rows commit in a single SQLite transaction.
- RabbitMQ publishing happens **outside** the upload's database transaction.
- At-least-once delivery (the future consumer must be idempotent on
  `eventId`/`jobId`).
- No production consumer, no `pos_document` rows, no PDF extraction, no OCR.
- Unrelated Task 1 endpoints keep their dummy behavior.

---

## 2. Files created or changed in the sprint

### Configuration / infrastructure

- [`src/main/resources/application.yaml`](../src/main/resources/application.yaml)
  — added `spring.servlet.multipart` (10 MiB / 11 MiB), `spring.rabbitmq`
  (correlated confirms, publisher returns, mandatory), and `app.upload` /
  `app.messaging` (validated `@ConfigurationProperties`).
- [`src/main/resources/db/migration/V2__create_outbox.sql`](../src/main/resources/db/migration/V2__create_outbox.sql)
  — `outbox_event` table + `ix_outbox_pending` index. (V1 untouched.)
- [`compose.yaml`](../compose.yaml) — fourth service `rabbitmq`
  (`rabbitmq:4.3.5-management`), AMQP `5672`, management `15672`,
  `rabbitmq-data` volume, health check, backend depends on
  `service_healthy`.
- [`.env.example`](../.env.example) — added `RABBITMQ_USERNAME` /
  `RABBITMQ_PASSWORD` (non-production values).
- [`pom.xml`](../pom.xml) — added `spring-boot-starter-amqp` (managed) and
  `org.testcontainers:testcontainers-rabbitmq` (managed, `test`).
- [`scripts/verify-container-stack.sh`](../scripts/verify-container-stack.sh)
  — extended (RabbitMQ health, management API, fixture upload, queue
  persistence across RabbitMQ restart, backend restart), preserving the
  corrected Compose-project guard.
- [`scripts/make-fixture.py`](../scripts/make-fixture.py) — (moved here in the
  corrective commit) deterministic generator + pinned SHA-256 assertion for
  the committed fixture.
- [`README.md`](../README.md) — operational documentation (endpoints,
  QUEUED semantics, at-least-once, orphan-object limitation, management
  ports).

### Controller layer (handwritten, generated interface untouched)

- [`controller/ApiExceptionHandler.java`](../src/main/java/horse/sumomo/pos_doc_backend/controller/ApiExceptionHandler.java)
  — `@RestControllerAdvice` mapping intake/HTTP failures to the generated
  `Problem` DTO with `application/problem+json`.
- [`controller/PosRecordsController.java`](../src/main/java/horse/sumomo/pos_doc_backend/controller/PosRecordsController.java)
  — `uploadPosRecord` is real (delegates to `PosArchiveIntakeService`,
  returns `202` + `Location`); other ops keep Task 1 dummy behavior.
- [`controller/IngestionJobsController.java`](../src/main/java/horse/sumomo/pos_doc_backend/controller/IngestionJobsController.java)
  — `getIngestionJob` is real (persisted lookup, `404` when missing/soft-deleted).

### Ingestion

- `ingestion/api/` — [`UploadLimitsProperties`](../src/main/java/horse/sumomo/pos_doc_backend/ingestion/api/UploadLimitsProperties.java),
  [`RabbitTopologyProperties`](../src/main/java/horse/sumomo/pos_doc_backend/ingestion/api/RabbitTopologyProperties.java),
  [`OutboxRelayProperties`](../src/main/java/horse/sumomo/pos_doc_backend/ingestion/api/OutboxRelayProperties.java)
  (validated `@ConfigurationProperties`; passwords never in `toString()`).
- `ingestion/archive/`
  - [`ArchiveFilenameParser`](../src/main/java/horse/sumomo/pos_doc_backend/ingestion/archive/ArchiveFilenameParser.java)
    — untrusted-filename parsing, eRef derivation.
  - [`BoundedUploadSpooler`](../src/main/java/horse/sumomo/pos_doc_backend/ingestion/archive/BoundedUploadSpooler.java)
    / [`SpooledUpload`](../src/main/java/horse/sumomo/pos_doc_backend/ingestion/archive/SpooledUpload.java)
    — streaming spool + SHA-256 + compressed-byte limit, `AutoCloseable`.
  - [`ZipArchiveValidator`](../src/main/java/horse/sumomo/pos_doc_backend/ingestion/archive/ZipArchiveValidator.java)
    / [`ValidatedArchive`](../src/main/java/horse/sumomo/pos_doc_backend/ingestion/archive/ValidatedArchive.java)
    — streaming ZIP/PDF validation (see §5).
  - [`ArchiveValidationException`](../src/main/java/horse/sumomo/pos_doc_backend/ingestion/archive/ArchiveValidationException.java)
    — stable, sanitized categories.
- `ingestion/application/`
  - [`PosArchiveIntakeService`](../src/main/java/horse/sumomo/pos_doc_backend/ingestion/application/PosArchiveIntakeService.java)
    — orchestration (validation → spool → validate → IDs → MinIO → DB →
    compensation).
  - [`IntakeDatabaseService`](../src/main/java/horse/sumomo/pos_doc_backend/ingestion/application/IntakeDatabaseService.java)
    — the single `@Transactional` commit of the four rows.
  - [`IntakeException`](../src/main/java/horse/sumomo/pos_doc_backend/ingestion/application/IntakeException.java)
    — stable problem codes (`Code` enum).
  - [`UploadCommand`](../src/main/java/horse/sumomo/pos_doc_backend/ingestion/application/UploadCommand.java),
    [`UploadResult`](../src/main/java/horse/sumomo/pos_doc_backend/ingestion/application/UploadResult.java),
    [`CurrentUploaderProvider`](../src/main/java/horse/sumomo/pos_doc_backend/ingestion/application/CurrentUploaderProvider.java)
    (fixed `AUTH_NOT_IMPLEMENTED`).
- `ingestion/messaging/`
  - [`RabbitTopologyConfiguration`](../src/main/java/horse/sumomo/pos_doc_backend/ingestion/messaging/RabbitTopologyConfiguration.java)
    — durable exchanges/queues/bindings.
  - [`IngestionRequestedMessage`](../src/main/java/horse/sumomo/pos_doc_backend/ingestion/messaging/IngestionRequestedMessage.java)
    — identifier-only record.
  - [`OutboxRelay`](../src/main/java/horse/sumomo/pos_doc_backend/ingestion/messaging/OutboxRelay.java)
    — scheduled, overlap-guarded, bounded batch.
  - [`OutboxEventStateService`](../src/main/java/horse/sumomo/pos_doc_backend/ingestion/messaging/OutboxEventStateService.java)
    — short transactional `markPublished` / `recordFailure` + detached snapshot load.
  - [`OutboxPublisher`](../src/main/java/horse/sumomo/pos_doc_backend/ingestion/messaging/OutboxPublisher.java)
    / [`RabbitOutboxPublisher`](../src/main/java/horse/sumomo/pos_doc_backend/ingestion/messaging/RabbitOutboxPublisher.java)
    — correlated-confirm publisher.
  - [`OutboxSchedulingConfiguration`](../src/main/java/horse/sumomo/pos_doc_backend/ingestion/messaging/OutboxSchedulingConfiguration.java)
    — `@EnableScheduling`.
- `ingestion/mapping/IngestionJobApiMapper` — persisted job → generated DTO.

### Persistence

- [`persistence/entity/OutboxEventEntity.java`](../src/main/java/horse/sumomo/pos_doc_backend/persistence/entity/OutboxEventEntity.java)
- [`persistence/repository/OutboxEventRepository.java`](../src/main/java/horse/sumomo/pos_doc_backend/persistence/repository/OutboxEventRepository.java)

### Tests

- `archive/ArchiveFilenameParserTest`, `archive/BoundedUploadSpoolerTest`,
  `archive/ZipArchiveValidatorTest`, `archive/FixtureIntegrityTest` (new in
  the corrective commit).
- `controller/ApiSkeletonTest` (extended with the required upload-failure
  contract in the corrective commit),
  `controller/UploadSizeRejectionIntegrationTest` (new in the corrective
  commit — real servlet-level oversize → 413).
- `application/PosArchiveIntakeIntegrationTest`,
  `messaging/OutboxRelayIntegrationTest`, `messaging/FullIntakeIntegrationTest`,
  `persistence/OutboxIntakeTransactionIntegrationTest`.

---

## 3. Versions resolved (from `mvn dependency:tree`)

| Component | Version |
|---|---|
| Spring Boot / parent | 4.1.1 |
| `org.springframework.amqp:spring-rabbit` | 4.1.1 (managed) |
| `com.rabbitmq:amqp-client` | 5.30.0 (managed) |
| `org.testcontainers:testcontainers` | 2.0.5 (managed) |
| `org.testcontainers:testcontainers-rabbitmq` | 2.0.5 (managed) |
| `org.testcontainers:testcontainers-minio` | 2.0.5 (managed) |
| `org.flywaydb:flyway-core` | 12.4.0 (managed) |
| `org.xerial:sqlite-jdbc` | 3.53.2.1 |
| `io.minio:minio` | 9.0.3 |
| MinIO image | `minio/minio:RELEASE.2025-09-07T16-13-09Z` |
| RabbitMQ image | `rabbitmq:4.3.5-management` |
| Java (runtime) | 25.0.4 |

No second Testcontainers version, no second JSON library, no new HTTP
framework — matching the plan's dependency constraints.

---

## 4. Transaction and MinIO-compensation behavior

`PosArchiveIntakeService.intake` runs, in order:

1. Request validation (multipart part, declared content-type allowlist,
   filename/eRef, optional policy number).
2. Bounded spooling with SHA-256 (streamed, never `getBytes()`).
3. ZIP/PDF validation (streamed, bounded buffer).
4. Generation of the four UUIDs and the PII-free object key
   `archives/{posRecordId}/{storageObjectId}.zip`.
5. MinIO upload via the adapter (streamed file input, exact known size).
6. `IntakeDatabaseService.persist(command)` — **one** `@Transactional` method
   that creates and flushes `storage_object`, `pos_record`
   (`status=UPLOADED`, `uploadedBy=AUTH_NOT_IMPLEMENTED`), `ingestion_job`
   (`status=QUEUED`, attempt `0`), and `outbox_event` (the exact serialized
   `IngestionRequestedMessage` as `payload_json`).
7. On database failure after a successful MinIO upload: delete **only** the
   newly generated object key; propagate the original categorized error; log
   only generated UUIDs and a stable category (object key only on a
   compensation failure, for orphan recovery).

Failure directions:

- MinIO upload fails → no database transaction begins; sanitized 500.
- Database fails after MinIO → scoped compensation of the new object.
- A crash between MinIO success and SQLite commit can leave an orphan object;
  reconciliation is deferred (documented as a known limitation).

---

## 5. ZIP limits and validation rules implemented

Configured limits (validated `@ConfigurationProperties`, defaults from
`application.yaml`):

| Limit | Value |
|---|---:|
| `max-compressed-bytes` | 10,485,760 (10 MiB, exactly) |
| `max-uncompressed-bytes` | 262,144,000 |
| `max-entry-bytes` | 52,428,800 |
| `max-file-entries` | 100 |
| `max-compression-ratio` | 100 |

`ZipArchiveValidator` enforces (based on **bytes actually read**, never on
`ZipEntry.getSize()`/`getCompressedSize()`):

- Recognized ZIP signature checked **before** opening the reader
  (`PK\003\004`, `PK\005\006`, `PK\007\008`); an unrecognized signature →
  `UNSUPPORTED_ARCHIVE_TYPE` (415); a file too small to carry any signature →
  `INVALID_ARCHIVE` (422).
- 1..100 non-directory entries; directories allowed, not counted.
- Every non-directory entry: case-insensitive `.pdf` suffix, first five
  bytes exactly `%PDF-`.
- Entry paths normalized (`\`→`/`); absolute, Windows drive-prefixed, empty,
  `.`, and `..` segments rejected.
- Duplicate normalized paths rejected (case-insensitive, `Locale.ROOT`).
- **Per-entry** uncompressed limit enforced **on every chunk read** — a
  compression bomb is aborted at the first limit-breaking chunk (see the
  corrective commit).
- **Total** uncompressed limit enforced after each entry.
- **Archive-level** compression ratio (total uncompressed ÷ spooled compressed
  bytes) enforced once at least one compressed byte is known; a zero
  compressed-size entry that produces output is rejected.
- Corrupt/truncated/encrypted archives → `INVALID_ARCHIVE`.
- Zero PDFs → rejected.
- PDF contents never retained; streams and the `ZipFile` are closed (temp
  file deletable on Windows).

---

## 6. RabbitMQ topology and retry schedule

Durable topology (names from `RabbitTopologyProperties`, no string literals):

```text
direct exchange:      pos.ingestion
queue:                pos.ingestion.jobs   (durable, x-dead-letter-exchange/key set)
routing key:          ingestion.requested

direct DLX:           pos.ingestion.dlx
dead-letter queue:    pos.ingestion.dead   (durable)
dead-letter key:      ingestion.dead
```

- Main queue bound to main exchange; dead-letter queue bound to DLX.
- Messages use **persistent** delivery mode.
- Publisher confirms are `correlated`; template is `mandatory`.

Outbox retry schedule (capped, applied by `OutboxEventEntity.recordFailure`):

```text
attempt 1: now + 1 second
attempt 2: now + 5 seconds
attempt 3: now + 30 seconds
attempt 4: now + 60 seconds
attempt 5+: now + 300 seconds
```

`markPublished` is idempotent (keeps the first instant); a published event
cannot be marked failed. The relay uses an `AtomicBoolean` guard against
overlap, processes at most `batch-size` (20) events per run, and loads the
batch with a database-level `Pageable` limit (corrective commit, §8.5).

---

## 7. Exact message fields and confirmation/routing behavior

`IngestionRequestedMessage` is the **only** payload, serialized **once** when
the outbox row is created and republished verbatim on every retry:

```json
{
  "eventId": "uuid",
  "jobId": "uuid",
  "posRecordId": "uuid",
  "schemaVersion": 1,
  "occurredAt": "UTC ISO-8601 instant"
}
```

It contains no eRef, policy number, names, filename, bucket/object key,
SHA-256, error text, or document content.

`RabbitOutboxPublisher.publish` sends through the auto-configured
`RabbitTemplate` with a `CorrelationData` and reports `true` **only** when:

- a positive (ack) publisher confirm arrives within `confirm-timeout-ms`
  (5000), **and**
- no mandatory `ReturnedMessage` is present.

Any nack, timeout, return, or broker failure reports `false`, leaving the
event unpublished for bounded retry. Broker exception messages are never
logged or propagated (they may contain connection strings/credentials); only
stable categories are logged.

AMQP message properties: `contentType=application/json`,
`contentEncoding=UTF-8`, `deliveryMode=persistent`, `messageId=eventId`,
`correlationId=jobId`, `type=INGESTION_REQUESTED`.

---

## 8. Corrective commit — findings addressed

### 8.1 (Blocking) ZIP expansion limits were checked too late

`ZipArchiveValidator` previously fully decompressed an entry before checking
the per-entry / total / ratio limits (the read-loop cutoff was
`Integer.MAX_VALUE`), so a compression bomb could inflate ~2 GiB before
rejection.

**Fix:** the per-entry limit is now enforced **inside the read loop, after
every chunk** (`bytesRead > maxEntryBytes` → throw immediately). Total
uncompressed and the running archive ratio are re-checked after each entry.
The read was also refactored into a package-visible, stream-oriented
`readAndValidatePdf(InputStream, long maxEntryBytes)` and `BUFFER_SIZE` made
package-visible so a test can pin the chunk size.

**New test:** `ZipArchiveValidatorTest.perEntryReadStopsAtTheFirstLimitBreakingChunk`
wraps the entry in a `CountingInputStream` and asserts the read stops at
exactly `5 + BUFFER_SIZE` bytes (the magic plus the first full chunk), never
inflating the second, limit-breaking chunk.

### 8.2 (Blocking) Non-ZIP data returned the wrong status

`checkSignature()` raised `INVALID_ARCHIVE` (mapped to 422) for an
unrecognized signature.

**Fix:** an unrecognized ZIP signature now raises
`UNSUPPORTED_ARCHIVE_TYPE` (mapped to 415). A file too small to carry a
signature (malformed/truncated) remains `INVALID_ARCHIVE` (422). The test
`nonZipBytesAreRejectedAsUnsupportedArchiveType` was corrected to assert the
415 category, and a new `fileTooSmallForASignatureIsRejectedAsInvalidArchive`
covers the 422 boundary.

### 8.3 (Blocking) Servlet-level oversized uploads fell into the 500 handler

`application.yaml` limits multipart files to 10 MiB, but
`ApiExceptionHandler` had no handler for Spring's
`MaxUploadSizeExceededException`, so a too-large upload rejected by the
servlet container before reaching `BoundedUploadSpooler` returned the generic
500.

**Fix:** added a `@ExceptionHandler(MaxUploadSizeExceededException)` mapping
to `413 ARCHIVE_TOO_LARGE` (the same stable code the spooler uses), with the
raw exception never logged.

**New test:** `UploadSizeRejectionIntegrationTest` drives the **real** servlet
layer (full Spring context + MockMvc, no mocked service) with a 10 MiB + 1
byte part and asserts `413` / `ARCHIVE_TOO_LARGE` / `application/problem+json`.

### 8.4 (Blocking) Required MVC failure tests were missing

`ApiSkeletonTest` covered the happy path and missing file only.

**Added** (mocking only the application-service boundary):
- Oversize → `413` `ARCHIVE_TOO_LARGE`.
- Unsupported type/signature → `415` `UNSUPPORTED_ARCHIVE_TYPE`.
- Invalid archive → `422` `INVALID_ARCHIVE`.
- Duplicate eRef → distinct `409` `DUPLICATE_EREF_NUMBER`.
- Duplicate policy → distinct `409` `DUPLICATE_POLICY_NUMBER`.
- Internal error → sanitized `500` `INGESTION_INTAKE_FAILED`, with an explicit
  assertion that the problem body contains **none** of the fixture eRef,
  policy number, raw database text, or constraint text.

### 8.5 (Non-blocking) Outbox batch limit was in memory

`OutboxEventRepository.findPendingDue` returned **every** due row and the
service applied `.limit(limit)` in memory.

**Fix:** the pending query now takes a `Pageable` argument
(`PageRequest.of(0, limit)`) so the batch limit is enforced at the database
level (SQL `LIMIT`), and `OutboxEventStateService.dueSnapshots` passes it
through. The two affected integration tests were updated to the new
`findPendingDue(Instant, Pageable)` signature.

### 8.6 (Non-blocking) Generic handler logged the raw exception

`handleUnexpected` logged the raw exception + stack trace, conflicting with
the plan's prohibition on logging raw database/broker/storage exception
messages.

**Fix:** it now logs only a stable category and the exception class simple
name — never the message or stack trace.

### 8.7 (Non-blocking) Fixture hash was not asserted / generator misplaced

`make-fixture.py` printed a hash but did not assert the fixture's expected
SHA-256, and `probe-src` looked like leftover scaffolding.

**Fix:** the generator was moved to
[`scripts/make-fixture.py`](../scripts/make-fixture.py) (removed from
`probe-src`), is CWD-independent, and **asserts** the committed fixture's
SHA-256 (exits non-zero on mismatch). A new
`FixtureIntegrityTest` pins the same hash on the test classpath so any
accidental replacement fails the build.

- Committed fixture `src/test/resources/fixtures/valid-two-pdf.zip`
  (330 bytes) SHA-256:
  `1ce96e72137fd1b084410d8f1f9154bce9dfd435fc9e9ab6a8ea340968e362a0`.

### 8.8 (Blocking) ZIP expansion limits must be enforced *during* each entry read

The previous corrective commit enforced the per-entry limit during the
bounded read but still only checked `maxUncompressedBytes` and
`maxCompressionRatio` **after** the entire entry had been decompressed. A
crafted archive could therefore consume memory up to the per-entry cap
before being rejected by the post-entry check.

**Fix:** [`ZipArchiveValidator`](../src/main/java/horse/sumomo/pos_doc_backend/ingestion/archive/ZipArchiveValidator.java)
now computes, *before* opening each entry's input stream, the
[`effectiveEntryLimit`](../src/main/java/horse/sumomo/pos_doc_backend/ingestion/archive/ZipArchiveValidator.java#L155-L196)
which is the minimum of three allowances, all computed with overflow-safe
arithmetic:

```text
remainingTotal = maxUncompressedBytes - totalUncompressedBeforeEntry
maximumRatioBytes = archiveCompressedBytes * maxCompressionRatio   (Math.multiplyExact)
remainingRatio = maximumRatioBytes - totalUncompressedBeforeEntry
effectiveEntryLimit = min(maxEntryBytes, remainingTotal, remainingRatio)
```

- If any remaining allowance is zero or negative, the entry is rejected
  **before** any bytes are read.
- The ratio product is computed with
  [`Math.multiplyExact`](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/Math.html#multiplyExact(long,long));
  on overflow the validator fails closed with `INVALID_ARCHIVE` rather
  than silently wrapping into a permissive cap.
- The cap is passed into the per-entry read loop, which already aborts on
  the first limit-breaking chunk, so the read can never inflate beyond
  one buffered chunk past the cap.
- The post-entry checks are kept as defensive assertions to guard
  against future refactors.

**New tests** in [`ZipArchiveValidatorTest`](../src/test/java/horse/sumomo/pos_doc_backend/ingestion/archive/ZipArchiveValidatorTest.java):

- `effectiveEntryLimitIsTheMinimumOfTheThreeAllowances` — exercises
  small, large, and partially-consumed archive scenarios and asserts the
  minimum is taken.
- `effectiveEntryLimitRejectsImmediatelyWhenTotalBudgetIsExhausted` —
  pre-entry rejection when the remaining total is zero.
- `effectiveEntryLimitRejectsImmediatelyWhenRatioBudgetIsExhausted` —
  pre-entry rejection when the remaining ratio is zero.
- `effectiveEntryLimitOverflowsFailClosed` — `archive * ratio` overflow
  is rejected as `INVALID_ARCHIVE`.
- `totalUncompressedBudgetAbortsFirstEntryAtTheFirstLimitBreakingChunk`
  and `totalBudgetAbortsSecondEntryAtTheFirstLimitBreakingChunk` —
  full-archive proofs that the second entry is rejected pre-entry once
  the first entry has consumed the total budget.
- `compressionRatioBudgetAbortsFirstEntryAtTheFirstLimitBreakingChunk`
  — full-archive proof that the per-entry cap is the binding constraint
  when the archive is highly compressible.
- `realZipEntryReadStopsAtTheFirstLimitBreakingChunkForPerEntryLimit` —
  reads through a real `ZipFile` entry wrapped in a counting stream and
  asserts the read stops at `5 + BUFFER_SIZE` bytes (magic + the first
  full buffer chunk), with `counting.count < entry.getSize()` to prove
  the stream was not read to completion.
- `readingAbortsAtTheFirstLimitBreakingChunkWhenTotalBudgetIsNearlyExhausted`
  — direct `readAndValidatePdf` proof of the chunk-level abort.

ZIP metadata (`ZipEntry.getSize()` / `getCompressedSize()`) is never
trusted for enforcement. PDF contents are never buffered; only the
bounded `BUFFER_SIZE` chunks are read.

### 8.9 (Blocking) Oversize-upload test did not exercise servlet multipart parsing

The previous [`UploadSizeRejectionIntegrationTest`](../src/test/java/horse/sumomo/pos_doc_backend/controller/UploadSizeRejectionIntegrationTest.java)
used `MockMvcRequestBuilders.multipart`, which builds an already-parsed
`MockMultipartFile` and therefore bypasses the servlet container's
multipart parser. The test proved the application-level mapping but
not that Spring's `MaxUploadSizeExceededException` was handled.

**Fix:** the single test was replaced by three test classes, each
covering one layer of the contract:

- [`MaxUploadSizeAdviceTest`](../src/test/java/horse/sumomo/pos_doc_backend/controller/MaxUploadSizeAdviceTest.java)
  — boots only the `ApiExceptionHandler` bean and invokes
  `handleMaxUploadSize` directly with a real
  `MaxUploadSizeExceededException`. Asserts `413`,
  `application/problem+json`, `ARCHIVE_TOO_LARGE`, sanitized detail, and
  the absence of any raw exception text in the body.
- [`UploadSizeRealHttpRejectionTest`](../src/test/java/horse/sumomo/pos_doc_backend/controller/UploadSizeRealHttpRejectionTest.java)
  — `@SpringBootTest(webEnvironment = RANDOM_PORT)` boots the full
  Spring context on a random port, then sends a real multipart HTTP
  request containing 10 MiB + 1 byte to the embedded Tomcat over a raw
  `HttpURLConnection`. The container's multipart parser raises
  `MaxUploadSizeExceededException`; the advice maps it to the 413
  problem response. The intake service is mocked and is asserted to
  have never been invoked. The test does not contact MinIO or RabbitMQ
  and uses a temporary SQLite database. The misleading comment that
  claimed MockMvc exercises servlet multipart parsing was removed.
- The pre-existing
  [`ApiSkeletonTest.oversizeUploadReturns413ArchiveTooLarge`](../src/test/java/horse/sumomo/pos_doc_backend/controller/ApiSkeletonTest.java)
  continues to cover the application-level MockMvc path (mocked service
  throws `IntakeException(ARCHIVE_TOO_LARGE)`).

Together these three tests cover the full chain: the advice in
isolation, the servlet container rejection in integration, and the
application-level mapping via MockMvc.

---

## 9. Verification

Commands executed (Windows `cmd.exe`, Git Bash for the shell script):

```text
mvnw.cmd clean verify                                    # PASS  (run 1)
mvnw.cmd clean verify                                    # PASS  (run 2)
docker compose --env-file .env.example config --quiet    # PASS
bash scripts/verify-container-stack.sh                   # ALL CHECKS PASSED
mvnw.cmd clean verify                                    # PASS  (post-stack)
git status --short                                       # 5 paths (1 modified production, 1 modified test, 1 deleted test, 2 new tests)
```

Final Maven result (after this commit):

```text
Tests run: 151, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

All Docker/Testcontainers-based tests executed against real containers
(no skips): `PosArchiveIntakeIntegrationTest` (7), `OutboxRelayIntegrationTest`
(5), `FullIntakeIntegrationTest` (1), `MinioObjectStorageIntegrationTest`,
plus MinIO/SQLite persistence tests. The new real-HTTP
`UploadSizeRealHttpRejectionTest` (1) successfully sent a 10 MiB + 1
byte multipart upload to the embedded Tomcat on a random port and
asserted the 413 response.

The whole-stack verification script (`scripts/verify-container-stack.sh`)
passed every check end-to-end:

- `== compose config ==` — exit 0.
- `== up --detach --wait ==` — all four services healthy
  (backend, minio, minio-init, rabbitmq).
- `== minio live check ==` → `minio: live`.
- `== backend health check ==` → `backend: UP`.
- `== sqlite file check ==` → `sqlite: database file present in backend container`.
- `== minio persistence marker ==` → `minio: marker uploaded`.
- `== restart minio ==` → `minio: data survived container restart`.
- `== restart backend ==` → `backend: healthy after restart`.
- `== rabbitmq health check ==` → `rabbitmq: healthy`.
- `== rabbitmq management API check ==` → `rabbitmq: management API authenticated`.
- `== stack upload (committed fixture) ==` → `upload: 202 with posRecordId and jobId`.
- `== job queryable and QUEUED ==` → `job: QUEUED`.
- `== queue ready message count ==` → `queue: exactly one ready message`.
- `== message body contains identifiers only ==` → `message: identifiers only, no fixture metadata`.
- `== restart rabbitmq ==` → `rabbitmq: healthy after restart`.
- `== verify durable message survived rabbitmq restart ==` → `queue: persistent message survived rabbitmq restart`.
- `== restart backend after rabbitmq restart ==` → `job: still QUEUED after backend restart`.
- `verify-container-stack: ALL CHECKS PASSED`.

---

## 10. Deviations from the plan

- **Fixture generator location:** the plan did not mandate a path for the
  generator; it was moved from `probe-src/` to `scripts/` per the reviewer to
  keep scaffolding out of `probe-src` and to add the required hash assertion.
- **`findPendingDue` signature:** the plan said "use an explicit JPQL query
  with a `Pageable` limit if a derived method becomes unreadable." The first
  commit used an explicit JPQL query but applied the limit in memory; this
  commit adds the `Pageable` parameter to satisfy the database-level batch
  limit requirement. No other public repository method changed shape except
  the (previously unversioned) internal test call sites.

No changes were made to Spring Boot, Java, Hibernate, SQLite, MinIO, or
existing Testcontainers versions, and no production consumer, `pos_document`
creation, OCR, authentication, or OpenAPI-contract change was introduced.
