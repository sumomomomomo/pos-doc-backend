# Tasks 4–5: Secure ZIP intake and reliable RabbitMQ enqueue

## Objective

Implement the first real ingestion boundary:

1. Accept a ZIP archive through the existing OpenAPI-generated multipart endpoint.
2. Validate the upload and the ZIP structure without trusting its filename or declared MIME type.
3. Support ZIP archives containing **multiple PDF files**.
4. Stream the accepted archive to MinIO under a generated, PII-free object key.
5. Persist the storage metadata, POS record, ingestion job, and an outbox event in SQLite.
6. Publish the outbox event to RabbitMQ with publisher confirms.
7. Return `202 Accepted` once the archive and database transaction are durable, even if RabbitMQ is temporarily unavailable.
8. Make the ingestion-job GET endpoint return the persisted job so the client can observe `QUEUED` state.

Use a transactional outbox. Never put ZIP bytes, PDF bytes, filenames, policy numbers, eRef numbers, names, OCR text, or other PII in RabbitMQ.

This combined task ends at the reliable queue boundary. It deliberately does **not** add the production ingestion consumer. The next task will consume the queued job and extract/process the PDFs. Until then, newly accepted jobs remain truthfully `QUEUED`.

This task is complete only when every command in **Verification order** succeeds.

## Important architecture

The successful path must be:

```text
HTTP multipart upload
        |
        v
bounded temporary file + SHA-256
        |
        v
ZIP validation (one or more PDFs)
        |
        v
MinIO source archive
        |
        v
one SQLite transaction:
  storage_object + pos_record + ingestion_job + outbox_event
        |
        v
202 Accepted

separate scheduled relay:
  unpublished outbox_event -> RabbitMQ -> publisher confirm -> mark published
```

The MinIO object must be durable before the database can refer to it. The outbox row must be committed in the same SQLite transaction as the POS record and job. RabbitMQ publishing must not happen inside the upload request's database transaction.

This produces **at-least-once** message delivery. A process crash after RabbitMQ confirms a publish but before SQLite records `published_at` can cause the same event to be published again. The future consumer must therefore be idempotent using `eventId` or `jobId`.

## Read before editing

Run the existing baseline from the repository root:

```text
Windows cmd.exe: mvnw.cmd clean verify
POSIX shell:     ./mvnw clean verify
```

If the baseline fails, stop and report the failure. Do not begin Tasks 4–5 on a broken baseline.

Inspect, without editing generated files:

```text
openapi/pos-document-api.openapi.yaml
target/generated-sources/openapi/          (after Maven generation)
src/main/java/.../controller/
src/main/java/.../infrastructure/minio/
src/main/java/.../persistence/
src/main/resources/application.yaml
src/main/resources/db/migration/
compose.yaml
.env.example
scripts/verify-container-stack.sh
```

Determine the exact generated Java signature for `uploadPosRecord` and `getIngestionJob` from the generated interface. Implement that signature; do not guess it and do not edit the generated interface.

## Strict scope

Implement only:

- Secure, bounded ZIP intake
- eRef derivation from the archive filename
- Optional policy-number acceptance
- MinIO storage of the original ZIP
- SQLite creation of storage/POS/job/outbox metadata
- MinIO compensation when the database transaction fails
- RabbitMQ topology and configuration
- Transactional-outbox relay with publisher confirms
- Persisted `getIngestionJob`
- Required error mapping for the upload endpoint
- Unit, integration, and whole-stack tests for this workflow

## Non-goals

Do **not** implement:

- A production RabbitMQ consumer or `@RabbitListener`
- Job transition from `QUEUED` to `RUNNING`
- PDF extraction from the stored ZIP
- Storing individual PDF objects in MinIO
- `pos_document` row creation
- OCR or vLLM
- Fuzzy search
- Real search, PATCH, DELETE, or document-list persistence wiring
- Google login, JWT validation, or authorization
- Malware scanning
- An orphan-object reconciliation scheduler
- Object retention/deletion policy
- WebSocket or server-sent-event status updates
- Additional OpenAPI endpoints
- Changes to the OpenAPI contract

The existing dummy behavior for unrelated endpoints must remain unchanged. Only `uploadPosRecord` and `getIngestionJob` become real in this task.

## Required dependencies

Add the Spring Boot-managed AMQP starter without an explicit version:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-amqp</artifactId>
</dependency>
```

Add the RabbitMQ Testcontainers module as a test dependency. Use the version already managed by Spring Boot when available:

```xml
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>testcontainers-rabbitmq</artifactId>
    <scope>test</scope>
</dependency>
```

The expected managed Testcontainers version at the time this plan was written is `2.0.5`. Do not introduce a second Testcontainers version.

Use only JDK ZIP support unless a concrete requirement below cannot be implemented safely with it. Do not add Apache Commons Compress merely for convenience.

Do not add PostgreSQL, Redis, Kafka, a second JSON library, or a second HTTP framework.

## Required package layout

Use the existing base package `horse.sumomo.pos_doc_backend`.

Create or update equivalent files under this structure:

```text
src/main/resources/db/migration/
└── V2__create_outbox.sql

src/main/java/horse/sumomo/pos_doc_backend/
├── controller/
│   ├── PosRecordsController.java
│   ├── IngestionJobsController.java
│   └── ApiExceptionHandler.java
├── ingestion/
│   ├── api/
│   │   ├── UploadLimitsProperties.java
│   │   └── RabbitTopologyProperties.java
│   ├── archive/
│   │   ├── ArchiveFilenameParser.java
│   │   ├── BoundedUploadSpooler.java
│   │   ├── SpooledUpload.java
│   │   ├── ZipArchiveValidator.java
│   │   ├── ValidatedArchive.java
│   │   └── ArchiveValidationException.java
│   ├── application/
│   │   ├── PosArchiveIntakeService.java
│   │   ├── IntakeDatabaseService.java
│   │   ├── UploadCommand.java
│   │   ├── UploadResult.java
│   │   └── CurrentUploaderProvider.java
│   ├── messaging/
│   │   ├── RabbitTopologyConfiguration.java
│   │   ├── IngestionRequestedMessage.java
│   │   ├── OutboxRelay.java
│   │   ├── OutboxPublisher.java
│   │   └── RabbitOutboxPublisher.java
│   └── mapping/
│       └── IngestionJobApiMapper.java
└── persistence/
    ├── entity/
    │   └── OutboxEventEntity.java
    └── repository/
        └── OutboxEventRepository.java
```

Names may be adjusted only to match an existing clear convention. Do not combine validation, MinIO access, database transaction handling, and Rabbit publishing into one controller method.

## Step 1: Add upload and RabbitMQ configuration

Merge the following concepts into the existing `application.yaml`. Preserve every existing setting and keep only one top-level `spring:` key.

```yaml
spring:
  servlet:
    multipart:
      max-file-size: 10MB
      max-request-size: 11MB
  rabbitmq:
    host: ${RABBITMQ_HOST:localhost}
    port: ${RABBITMQ_PORT:5672}
    username: ${RABBITMQ_USERNAME:local-dev-rabbit}
    password: ${RABBITMQ_PASSWORD:local-dev-rabbit-secret-change-me}
    publisher-confirm-type: correlated
    publisher-returns: true
    template:
      mandatory: true

app:
  upload:
    max-compressed-bytes: 10485760
    max-uncompressed-bytes: 262144000
    max-entry-bytes: 52428800
    max-file-entries: 100
    max-compression-ratio: 100
  messaging:
    outbox:
      enabled: ${OUTBOX_RELAY_ENABLED:true}
      fixed-delay-ms: 1000
      batch-size: 20
      confirm-timeout-ms: 5000
    topology:
      exchange: pos.ingestion
      routing-key: ingestion.requested
      queue: pos.ingestion.jobs
      dead-letter-exchange: pos.ingestion.dlx
      dead-letter-routing-key: ingestion.dead
      dead-letter-queue: pos.ingestion.dead
```

Bind upload limits and topology names with validated `@ConfigurationProperties` classes.

Validation requirements:

- All byte/count limits are positive.
- `max-entry-bytes <= max-uncompressed-bytes`.
- `max-compressed-bytes` is exactly 10 MiB unless the OpenAPI contract is changed in a future task.
- Rabbit names are non-blank.
- Do not expose passwords in a properties `toString()`.

The Spring multipart limit is an outer HTTP guard. `BoundedUploadSpooler` must independently enforce `max-compressed-bytes`; do not rely only on the servlet container.

## Step 2: Create the V2 outbox migration

Create exactly:

```text
src/main/resources/db/migration/V2__create_outbox.sql
```

Create `outbox_event` with these columns:

| Column | SQLite type | Rules |
|---|---:|---|
| `id` | `TEXT` | Primary key; UUID string |
| `aggregate_type` | `TEXT` | Not null; must equal `INGESTION_JOB` |
| `aggregate_id` | `TEXT` | Not null; job UUID string |
| `event_type` | `TEXT` | Not null; must equal `INGESTION_REQUESTED` |
| `payload_json` | `TEXT` | Not null |
| `created_at_epoch_ms` | `INTEGER` | Not null |
| `published_at_epoch_ms` | `INTEGER` | Nullable |
| `attempt_count` | `INTEGER` | Not null; default 0; check >= 0 |
| `next_attempt_at_epoch_ms` | `INTEGER` | Not null |

Add:

```sql
CREATE INDEX ix_outbox_pending
    ON outbox_event (published_at_epoch_ms, next_attempt_at_epoch_ms, created_at_epoch_ms);
```

Do not add raw exception text, stack traces, policy metadata, names, filenames, or object keys to the outbox table.

Do not edit V1 after it has been committed. Schema evolution must be V2.

## Step 3: Implement `OutboxEventEntity`

Follow the Task 3 entity conventions:

- UUID mapped explicitly to SQLite `TEXT`/VARCHAR
- Protected JPA constructor
- Application-generated UUID
- `Instant` fields use the existing epoch-millisecond converter
- PII-safe `toString()`
- No Lombok

Required behavior:

- New events start with `publishedAt = null`, `attemptCount = 0`, and `nextAttemptAt = createdAt`.
- `markPublished(Instant)` sets `publishedAt` once and is idempotent.
- `recordFailure(Instant now)` increments `attemptCount` and calculates the next attempt using this exact capped schedule:

```text
attempt 1: now + 1 second
attempt 2: now + 5 seconds
attempt 3: now + 30 seconds
attempt 4: now + 60 seconds
attempt 5 and later: now + 300 seconds
```

- Never store the exception message.
- A published event cannot be marked failed afterward.

Required repository methods:

```java
List<OutboxEventEntity> find...pending batch ordered by createdAt ascending;
Optional<OutboxEventEntity> findById(UUID id);
long countByPublishedAtIsNull();
```

For the pending batch, use an explicit JPQL query with a `Pageable` limit if a derived method becomes unreadable. It must select:

```text
publishedAt IS NULL
AND nextAttemptAt <= supplied now
ORDER BY createdAt ASC
```

## Step 4: Define the RabbitMQ message

`IngestionRequestedMessage` is the only message payload in this task.

Use an immutable Java record containing exactly:

```java
UUID eventId
UUID jobId
UUID posRecordId
int schemaVersion
Instant occurredAt
```

`schemaVersion` must be `1`.

The serialized JSON must use these exact property names:

```json
{
  "eventId": "uuid",
  "jobId": "uuid",
  "posRecordId": "uuid",
  "schemaVersion": 1,
  "occurredAt": "UTC ISO-8601 instant"
}
```

The payload must not contain:

- eRef number
- Policy number
- Policyholder or consultant name
- Filename
- MinIO bucket or object key
- SHA-256
- Error text
- Document content

Serialize the message once when creating the outbox row. Store that exact JSON in `payload_json`; do not reconstruct a potentially different message during each retry.

Use the application's configured Jackson mapper. Do not instantiate a separate mapper with different date handling.

## Step 5: Declare RabbitMQ topology

Create durable Spring AMQP beans for:

```text
direct exchange:      pos.ingestion
queue:                pos.ingestion.jobs
routing key:          ingestion.requested

direct DLX:           pos.ingestion.dlx
dead-letter queue:    pos.ingestion.dead
dead-letter key:      ingestion.dead
```

Use the configured names rather than repeating string literals throughout the code.

Bind the main queue to the main exchange. Bind the dead-letter queue to the DLX.

Configure the main queue with its DLX and dead-letter routing key. For this single-node learning deployment, declaring these queue arguments in application configuration is acceptable. Do not add TTL, maximum queue length, auto-delete, exclusivity, or a production consumer.

All exchanges and queues must be durable. Messages must use persistent delivery mode.

Do not use the default exchange or a fanout exchange.

## Step 6: Implement filename and eRef parsing

`ArchiveFilenameParser` must treat the multipart original filename as untrusted input.

Rules, in this exact order:

1. Reject `null` or blank original filenames.
2. Replace backslashes with `/` and retain only the final path segment. This handles browser-provided paths such as `C:\\fakepath\\EREF-001.zip` without trusting directories.
3. Reject a final segment that is blank, `.` or `..`.
4. Require a case-insensitive `.zip` suffix.
5. Remove only the final `.zip` suffix.
6. Trim the resulting base name.
7. Require the display eRef length to be between 1 and 256 Unicode code points.
8. Normalize it with the existing atomic `MetadataNormalizer.normalizeIdentifier`.

Return both:

```text
safe original filename segment, including .zip
display eRef, excluding .zip
normalized eRef
```

Do not log any of those values.

Examples:

```text
EREF-2026-001.zip                 -> display "EREF-2026-001"
C:\fakepath\EREF-2026-001.ZIP    -> display "EREF-2026-001"
../../EREF-2026-001.zip           -> display "EREF-2026-001"
.zip                              -> reject
archive.pdf                       -> reject
```

The path components are discarded rather than used. The archive is never extracted using the submitted paths.

## Step 7: Spool the compressed upload safely

`BoundedUploadSpooler` must copy the multipart input stream to a unique temporary file while calculating SHA-256 and enforcing the compressed-byte limit.

Requirements:

- Use `Files.createTempFile` or a dedicated application temp directory.
- Stream with a fixed-size buffer. Never call `MultipartFile.getBytes()`.
- Count actual bytes read. Do not trust `MultipartFile.getSize()`.
- Abort as soon as byte count exceeds `10,485,760`.
- Reject an empty upload.
- Calculate SHA-256 during the same copy pass.
- Return a `SpooledUpload` containing only the temp `Path`, exact byte count, and lowercase 64-character SHA-256.
- The returned object must implement `AutoCloseable` and delete only its own temporary file on close.
- Delete partial files after every failure.
- Never log the temp path, original filename, hash, or file contents.

The intake service must use try-with-resources around `SpooledUpload`, ensuring deletion on success and failure.

## Step 8: Validate the ZIP and all PDF entries

`ZipArchiveValidator` validates the spooled file without extracting files to disk.

The ZIP represents one POS record and may contain **multiple PDF files**. Validate every non-directory entry.

Requirements:

1. Verify the file begins with a recognized ZIP signature before opening it:
   - `PK\003\004`
   - `PK\005\006` is structurally an empty ZIP but must ultimately fail because no PDFs exist.
   - `PK\007\008` may be accepted only if the JDK ZIP reader successfully parses it.
2. Iterate with a JDK ZIP reader and fully read each file entry through a bounded buffer.
3. Directory entries are allowed and do not count toward the file-entry limit.
4. Require between 1 and 100 non-directory entries.
5. Every non-directory entry must have a case-insensitive `.pdf` suffix.
6. Normalize entry paths for validation by replacing `\\` with `/`.
7. Reject absolute paths, Windows drive-prefixed paths, empty path segments, `.` segments, and `..` segments.
8. Reject duplicate normalized entry paths. Compare duplicates case-insensitively with `Locale.ROOT` because later extraction will run on a case-sensitive Linux filesystem but users may create archives on Windows.
9. Fully read each PDF and reject an entry exceeding 52,428,800 uncompressed bytes.
10. Reject total uncompressed file bytes exceeding 262,144,000.
11. Reject an archive whose running total uncompressed/compressed ratio exceeds 100 once at least one compressed byte is known. Treat a zero compressed-size entry with nonzero output as suspicious and reject it.
12. Require every PDF's first five uncompressed bytes to be exactly `%PDF-`.
13. Reject corrupt, truncated, unsupported, or encrypted archives as `ArchiveValidationException`.
14. Reject any archive with zero PDFs.
15. Do not retain PDF contents after validation.

Do not trust `ZipEntry.getSize()` or `getCompressedSize()` as the only enforcement mechanism. Those values may be unknown or dishonest. Enforce uncompressed limits using bytes actually read.

For compression ratio, use actual total uncompressed bytes and the spooled archive's actual compressed byte count as a conservative archive-level ratio. Do not divide by an entry's unknown compressed size.

`ValidatedArchive` must contain only non-sensitive structural facts required by tests, such as:

```text
pdfCount
totalUncompressedBytes
```

Do not include entry filenames in its `toString()`.

## Step 9: MIME and request validation

The upload endpoint must require:

- Multipart part named `file`
- Optional part/field `policyNumber`
- Original filename ending in `.zip`, case-insensitive
- Declared content type in this allowlist when a content type is supplied:

```text
application/zip
application/x-zip-compressed
application/octet-stream
```

A missing content type is allowed because the ZIP signature and parser are authoritative. A declared content type outside the allowlist returns `415`, even if the bytes happen to form a ZIP.

Validate optional policy number with the generated OpenAPI constraints and `MetadataNormalizer`. Do not accept blank policy numbers. Preserve the display value; persist the normalized value through the entity's atomic setter.

Do not accept a client-supplied eRef. It is derived from the final filename segment.

## Step 10: Define the intake command and generated identifiers

`UploadCommand` must carry only:

```text
safe original filename
display eRef
optional display policy number
content type
spooled path
compressed byte count
SHA-256
validated PDF count
uploader subject
request timestamp
```

Until authentication is implemented, use the exact placeholder uploader subject:

```text
AUTH_NOT_IMPLEMENTED
```

Keep this constant in one clearly named class or provider (`CurrentUploaderProvider`). Do not accept `uploadedBy` from the request. A future authentication task will replace this provider.

Generate these UUIDs before MinIO upload:

```text
storageObjectId
posRecordId
jobId
outboxEventId
```

Generate a PII-free MinIO object key in this exact shape:

```text
archives/{posRecordId}/{storageObjectId}.zip
```

Never place the original filename, eRef, policy number, uploader, or hash in the object key.

## Step 11: Persist the accepted upload atomically

Separate orchestration from the database transaction:

### `PosArchiveIntakeService`

This service owns:

1. Filename/content-type/request validation.
2. Spooling and SHA-256.
3. ZIP/PDF validation.
4. ID and object-key generation.
5. MinIO upload.
6. Calling the database service.
7. MinIO compensation if the database operation fails.
8. Returning `UploadResult`.

### `IntakeDatabaseService`

This separate Spring service owns one `@Transactional` method which creates and flushes:

1. `StorageObjectEntity`
2. `PosRecordEntity`
3. `IngestionJobEntity`
4. `OutboxEventEntity`

Do not put `@Transactional` on a private method or rely on same-class self-invocation.

Persist initial values exactly:

```text
StorageObjectEntity:
  originalFilename = safe final filename segment
  contentType = application/zip
  byteSize = actual compressed bytes
  sha256 = calculated digest
  createdAt = request timestamp

PosRecordEntity:
  eRef = filename-derived display value
  policyNumber = optional request value
  status = UPLOADED
  uploadedBy = AUTH_NOT_IMPLEMENTED
  uploadedAt = request timestamp
  updatedAt = request timestamp
  deletedAt = null

IngestionJobEntity:
  status = QUEUED
  attemptCount = 0
  errorCode = null
  errorMessage = null
  createdAt = request timestamp
  startedAt = null
  completedAt = null

OutboxEventEntity:
  aggregateType = INGESTION_JOB
  aggregateId = jobId
  eventType = INGESTION_REQUESTED
  payloadJson = exact serialized IngestionRequestedMessage
  createdAt = request timestamp
  nextAttemptAt = request timestamp
```

Flush before returning so uniqueness and foreign-key violations occur inside the method.

The PDF count is validation evidence only in this task. Do not create `pos_document` rows until PDFs are actually extracted and stored.

## Step 12: MinIO upload and compensation

Use the existing `MinioObjectStorage` adapter. Do not call `MinioClient` directly from the intake service.

Upload using a file input stream and the exact known compressed size:

```text
object key: generated PII-free key
content type: application/zip
size: actual spooled byte count
```

Do not buffer the archive into a byte array.

If MinIO upload fails:

- Do not begin the database transaction.
- Return a sanitized server error.
- Leave no database rows or outbox event.

If MinIO succeeds but the database transaction fails:

1. Attempt to delete only the newly generated MinIO object key.
2. If compensation succeeds, propagate the original categorized error.
3. If compensation fails, preserve the original failure as the main cause and attach/log a sanitized compensation failure.
4. Log only generated UUIDs and a stable error category. The generated object key is UUID-only and may be logged at warning level for orphan recovery; never log filename/eRef/policy/name/hash/content.

There remains a narrow crash window after MinIO succeeds and before SQLite commits. A crash can leave an unreferenced MinIO object. Document this as a known limitation for a future orphan reconciler. Prefer this failure direction because the database must never claim an archive exists before it is durable in MinIO.

## Step 13: Make `uploadPosRecord` real

Update the handwritten controller implementation of the generated `uploadPosRecord` operation.

Requirements:

- Keep the generated interface as the source of request/response types.
- Delegate all work to `PosArchiveIntakeService`.
- Return `202 Accepted` with the generated `UploadAccepted` DTO.
- Return:

```text
posRecordId = persisted POS record ID
jobId = persisted ingestion job ID
status = UPLOADED
```

- Set `Location` to:

```text
/api/v1/pos-records/{posRecordId}
```

- Do not publish to RabbitMQ directly from the controller or intake service.
- Do not read ZIP bytes in the controller.
- Do not log the multipart request or DTO.

The remaining POS controller operations keep their current Task 1 dummy behavior in this task. Add a concise TODO stating that Task 1 dummy reads/search/update/delete are not yet persistence-backed. Do not silently pretend they are real in documentation.

## Step 14: Make `getIngestionJob` real

Replace only the dummy implementation of `getIngestionJob`.

Requirements:

- Query `IngestionJobRepository.findByIdAndPosRecordDeletedAtIsNull`.
- Return `404` when the job does not exist or its POS record is soft-deleted.
- Map every persisted field to the generated `IngestionJob` DTO.
- Keep error information sanitized.
- Do not initialize unrelated lazy collections.
- A freshly uploaded job must return `QUEUED`, attempt count `0`, and null start/completion/error fields.

The dummy ingestion-job service may be removed if no test or production class uses it afterward.

## Step 15: Map upload errors to the OpenAPI problem format

Create or extend one `@RestControllerAdvice`. Return `application/problem+json` using the generated `Problem` DTO where practical.

Use stable codes and statuses:

| Condition | HTTP | Code |
|---|---:|---|
| Missing multipart `file` | 400 | `MISSING_FILE` |
| Blank/invalid policy number | 400 | `INVALID_POLICY_NUMBER` |
| Missing/invalid filename or eRef derivation | 400 | `INVALID_ARCHIVE_FILENAME` |
| Compressed upload exceeds 10 MiB | 413 | `ARCHIVE_TOO_LARGE` |
| Disallowed declared MIME type or non-ZIP signature | 415 | `UNSUPPORTED_ARCHIVE_TYPE` |
| Corrupt/encrypted ZIP, unsafe path, non-PDF entry, invalid PDF signature, too many entries, decompression limit or ratio violation | 422 | `INVALID_ARCHIVE` |
| Duplicate normalized eRef | 409 | `DUPLICATE_EREF_NUMBER` |
| Duplicate normalized non-null policy number | 409 | `DUPLICATE_POLICY_NUMBER` |
| Missing/deleted ingestion job | 404 | `INGESTION_JOB_NOT_FOUND` |
| MinIO/database/unexpected failure | 500 | `INGESTION_INTAKE_FAILED` |

Do not determine duplicate type by parsing locale-dependent SQLite exception strings if a safe repository pre-check plus constraint classification can distinguish it. Pre-checks improve user-facing errors but do not replace database uniqueness enforcement. If SQLite exposes only a constraint message for the race case, parse only the stable index/column identifier and cover it with an integration test.

Problem responses must not include:

- Submitted filename
- eRef or policy number
- Entry name
- Temp path
- MinIO key
- SHA-256
- Database exception text
- RabbitMQ address or credentials
- Stack trace

Include a safe request/trace identifier only if the project already has one. Do not add a full tracing framework in this task.

## Step 16: Implement the outbox relay

### Scheduling

Enable scheduling in one configuration class.

Create `OutboxRelay` only when:

```text
app.messaging.outbox.enabled=true
```

Its scheduled method runs at the configured fixed delay. Prevent overlapping invocations within the same JVM using an `AtomicBoolean`, lock, or equivalent local guard. Do not add ShedLock; the deployment currently has one backend instance.

Process at most the configured batch size per run.

### Transaction boundaries

Do not hold a SQLite transaction open while waiting for the network.

For each due event:

1. Load a detached snapshot of the event ID and stored payload.
2. Publish to RabbitMQ outside a database transaction.
3. Wait for a positive publisher confirm within 5 seconds.
4. Treat an unroutable mandatory return as failure.
5. On success, call a separate transactional method which reloads the row and marks it published only if still unpublished.
6. On failure, call a separate transactional method which reloads the row, increments attempt count, and schedules the next attempt only if still unpublished.

Do not annotate one method containing all six operations with `@Transactional`.

### Message properties

Publish the exact stored JSON with:

```text
exchange       = configured pos.ingestion exchange
routing key    = configured ingestion.requested key
content type   = application/json
content encoding = UTF-8
delivery mode  = persistent
messageId      = outbox event UUID
correlationId  = ingestion job UUID
type           = INGESTION_REQUESTED
```

Use publisher confirms and mandatory returns. `convertAndSend` returning normally without a confirm is not proof of durable publication.

Use the managed Spring AMQP API available in the project. If the exact confirm-wait method differs from older examples, inspect the resolved API/Javadocs and choose the supported correlated-confirm mechanism. Do not disable confirms to make tests pass.

### Failure behavior

- RabbitMQ being unavailable must not roll back an already accepted upload.
- The outbox row remains unpublished and is retried.
- Do not mark the ingestion job `FAILED` merely because the broker is temporarily unavailable.
- Do not retry in a tight loop.
- Do not store or log raw broker exception messages if they may contain credentials or connection strings.
- Do not delete published outbox rows in this task.

## Step 17: Docker Compose RabbitMQ service

Add a fourth long-running service to `compose.yaml`:

```text
rabbitmq
```

Use this pinned official image:

```text
rabbitmq:4.3.5-management
```

Requirements:

- Environment:
  - `RABBITMQ_DEFAULT_USER=${RABBITMQ_USERNAME:?RABBITMQ_USERNAME is required}`
  - `RABBITMQ_DEFAULT_PASS=${RABBITMQ_PASSWORD:?RABBITMQ_PASSWORD is required}`
- Publish AMQP port `5672:5672`.
- Publish management UI port `15672:15672` for local development only.
- Mount named volume `rabbitmq-data` at `/var/lib/rabbitmq`.
- Restart policy `unless-stopped`.
- Health check with `rabbitmq-diagnostics -q ping`.
- Health-check interval 5 seconds, timeout 5 seconds, retries 20, start period 20 seconds.
- No host networking, privileged mode, bind-mounted credentials, or guest credentials.

Update the backend service:

- Depend on `rabbitmq` with `condition: service_healthy`.
- Set `RABBITMQ_HOST=rabbitmq`.
- Set port, username, and password from Compose variables.
- Keep the existing MinIO and SQLite settings.

Add the named volume:

```yaml
volumes:
  minio-data:
  sqlite-data:
  rabbitmq-data:
```

Update `.env.example` with non-production values:

```dotenv
RABBITMQ_USERNAME=local-dev-rabbit
RABBITMQ_PASSWORD=local-dev-rabbit-secret-change-me
```

Do not commit a real `.env`.

The management UI is available locally at `http://localhost:15672`. Do not expose it through the Spring application or a public reverse proxy.

## Step 18: Test fixtures

Tests need valid ZIPs containing multiple dummy PDFs. Build them programmatically with `ZipOutputStream` in test code.

Use minimal synthetic PDF bytes beginning with `%PDF-`, for example:

```text
%PDF-1.4
% dummy test document
%%EOF
```

These fixtures are parser-validation fixtures only; they do not need to render as complete real PDFs because this task validates the signature and archive structure, not PDF syntax.

Never use real insurance documents, names, policy numbers, filenames, or production-derived samples.

Create helper methods rather than committing dozens of binary ZIP variants.

For the whole-stack shell test, commit exactly one tiny synthetic fixture at:

```text
src/test/resources/fixtures/valid-two-pdf.zip
```

It must contain exactly:

```text
documents/first.pdf
documents/second.pdf
```

Both entries contain only the dummy bytes above. Document the fixture's SHA-256 in a nearby text comment or test assertion so accidental replacement is detected. A deliberately synthetic committed test fixture is allowed; runtime-generated ZIP/database/MinIO files are not.

## Step 19: Unit tests for filename, spooling, and ZIP validation

### `ArchiveFilenameParserTest`

Test:

- Normal `.zip`
- Uppercase `.ZIP`
- Windows fake path
- Forward-slash path
- Blank, null, `.zip`, wrong suffix
- More than 256 Unicode code points
- A filename whose base normalizes to empty punctuation
- No filename or eRef appears in exception messages

### `BoundedUploadSpoolerTest`

Test:

- Exact bytes and SHA-256 are preserved
- Empty input rejected
- Exactly 10 MiB accepted
- 10 MiB plus one byte rejected without reading the remainder
- Partial temp file removed after failure
- `close()` removes only the owned temp file and is idempotent
- Source input stream is closed by its owner according to the chosen method contract; state that contract in Javadoc

Do not allocate a 10 MiB boxed collection. Use streams or byte arrays of reasonable size.

### `ZipArchiveValidatorTest`

Programmatically create archives covering:

- One valid PDF
- Multiple valid PDFs in nested directories
- Empty ZIP
- Directory-only ZIP
- Non-ZIP bytes
- Truncated ZIP
- `.txt` entry
- PDF filename with non-PDF magic
- Absolute entry path
- `..` traversal using `/`
- `..` traversal using `\\`
- `.` or empty path segment
- Windows drive-prefixed entry
- Duplicate exact path
- Duplicate case-insensitive path
- 101 file entries
- Per-entry uncompressed limit
- Total uncompressed limit
- Compression-ratio limit using highly compressible bytes
- Corrupt/encrypted input when a deterministic fixture can be generated with available tools

Tests must prove every stream and ZIP reader is closed and temp files can be deleted on Windows after validation.

## Step 20: Database/outbox transaction tests

Use a real temporary SQLite database and real repositories.

Test:

1. The V2 migration creates `outbox_event` and its index.
2. A database intake call commits all four rows together.
3. The stored outbox JSON contains only the five allowed message fields.
4. The job starts `QUEUED` with attempt count zero.
5. The record starts `UPLOADED`.
6. The source archive object key uses only the generated UUID path structure.
7. A duplicate eRef rolls back storage metadata, record, job, and outbox rows from that transaction.
8. A duplicate policy number does the same.
9. `recordFailure` follows the exact retry schedule.
10. `markPublished` is idempotent.
11. Published events no longer appear in pending queries.

Flush constraint tests inside the assertion.

## Step 21: Real MinIO intake integration tests

Use a real Testcontainers MinIO instance and a real temporary SQLite database. Disable the scheduled outbox relay for tests that do not start RabbitMQ.

Test the real `PosArchiveIntakeService`:

1. A valid ZIP containing two PDFs is stored byte-for-byte in MinIO.
2. The `storage_object`, `pos_record`, `ingestion_job`, and `outbox_event` rows exist.
3. No `pos_document` rows exist yet.
4. The object key contains only generated UUIDs and `.zip`.
5. The original filename is stored only as metadata, not in the object key or outbox JSON.
6. Invalid ZIP/PDF/traversal inputs make no MinIO object and no database rows.
7. A duplicate eRef after MinIO upload causes the newly uploaded object to be deleted while preserving the original object's metadata and bytes.
8. A simulated database failure after MinIO upload invokes compensation for only the new generated object key.

Do not mock MinIO in the primary happy-path integration test.

For the simulated compensation failure path, a focused mock-based unit test is allowed because reliably forcing both SQLite and MinIO to fail in sequence would make the test nondeterministic.

## Step 22: Real RabbitMQ outbox integration tests

Use `RabbitMQContainer` with:

```text
rabbitmq:4.3.5-management
```

Use the project-managed Testcontainers version. Use a unique temporary SQLite database. MinIO is not required for relay-only tests.

Disable automatic scheduling and invoke one relay cycle directly through a package-visible method or dedicated service. Do not sleep waiting for the scheduler.

Test:

1. Spring declares both exchanges, both queues, and both bindings.
2. An unpublished outbox event is sent to `pos.ingestion.jobs`.
3. The received message body exactly matches stored `payload_json`.
4. Message properties are persistent and contain the required IDs/type/content metadata.
5. A positive publisher confirm results in non-null `publishedAt`.
6. The published row no longer appears in the pending query.
7. With an unroutable routing key and mandatory publishing, the row remains unpublished and its attempt count increases.
8. With the broker unavailable, the row remains unpublished, its retry timestamp advances, and no ingestion job is marked failed.
9. Retrying after a temporary failure publishes the original stored JSON, not reconstructed JSON.
10. Calling the relay again after success does not publish the row again.

Use bounded receives/timeouts. Do not use arbitrary multi-second sleeps.

The test should consume the message only for assertion. Do not add a production listener.

## Step 23: MVC contract tests

Update controller tests to reflect the now-real upload and job GET operations while preserving unrelated Task 1 tests.

Use `MockMvc` and mock only the application service boundary in MVC slice tests.

Test:

- Valid multipart upload returns `202`, `Location`, persisted IDs from the mocked result, and `UPLOADED`.
- Missing file is `400`.
- Oversize is `413`.
- Unsupported content type is `415`.
- Invalid archive is `422`.
- Duplicate eRef and policy produce distinct `409` codes.
- Internal error is sanitized `500`.
- `getIngestionJob` maps a real queued entity to the generated DTO.
- Missing/deleted job is `404`.
- Problem responses use `application/problem+json`.
- No problem detail contains fixture filename, policy number, entry name, object key, database message, or credentials.

Do not weaken the existing non-UUID path-parameter tests.

## Step 24: Full intake integration test

Add one test using:

- Real Spring context
- Real temporary SQLite
- Real Testcontainers MinIO
- Real Testcontainers RabbitMQ
- Real controller/service/repositories/outbox relay
- `MockMvc` only as the HTTP client

Workflow:

1. Submit a multipart ZIP named `EREF-TASK45-001.zip` containing two dummy PDFs and optional policy `POLICY-TASK45-001`.
2. Assert `202` and capture `posRecordId` and `jobId`.
3. Assert the Location header uses `posRecordId`.
4. Verify the MinIO archive bytes match the uploaded ZIP.
5. Verify all expected SQLite rows exist and no `pos_document` exists.
6. Invoke the relay once.
7. Receive exactly one RabbitMQ message from the real queue.
8. Assert it contains only the generated identifiers, schema version, and timestamp.
9. Call `GET /ingestion-jobs/{jobId}` and assert `QUEUED`.
10. Assert the outbox row is marked published.

Do not make test order significant. Use unique IDs and clean test infrastructure.

## Step 25: Extend whole-stack verification

Update `scripts/verify-container-stack.sh` without weakening its corrected existing-stack refusal guard.

Preserve:

- Fixed Compose project name `pos-doc-task2-test`, unless the script is deliberately renamed everywhere in a separate cleanup
- Correct Docker label `com.docker.compose.project`
- Scoped cleanup trap
- Dedicated temporary env file passed to every Compose command
- Existing MinIO and SQLite restart/persistence checks
- No unscoped prune/down operations

Add RabbitMQ credentials to the temporary env file.

Add checks:

1. RabbitMQ container reports healthy.
2. RabbitMQ management API responds on port 15672 using test credentials without printing them.
3. Upload the committed `valid-two-pdf.zip` fixture using `curl` multipart form and filename `EREF-STACK-001.zip`.
4. Assert `202` and capture `posRecordId`/`jobId` using POSIX tools already available in Git Bash. Do not require `jq`.
5. Poll `GET /api/v1/ingestion-jobs/{jobId}` and assert `QUEUED`.
6. Poll RabbitMQ with a bounded loop until `pos.ingestion.jobs` has exactly one ready message.
7. Inspect one message through a safe RabbitMQ CLI/management operation and verify no ZIP bytes or fixture metadata appear in the body. If inspecting consumes the message, explicitly requeue it.
8. Restart only RabbitMQ.
9. Wait for RabbitMQ health with a bounded loop.
10. Verify the durable queue and persistent message survive the restart.
11. Restart the backend and verify the job remains queryable and queued.
12. Preserve all existing MinIO marker and SQLite checks.

The script must avoid printing RabbitMQ or MinIO passwords. Use `curl --user` only in a command whose expanded arguments are not echoed; keep shell tracing disabled.

The cleanup trap must remove the script's RabbitMQ volume because it belongs to the dedicated test project. It must never affect another Compose project.

## Operational behavior to document

Add concise project documentation covering:

- Local endpoints:
  - Backend: `http://localhost:8080/api/v1`
  - MinIO API: `http://localhost:9000`
  - MinIO console: `http://localhost:9001`
  - RabbitMQ AMQP: `localhost:5672`
  - RabbitMQ management: `http://localhost:15672`
- Start with `.env.example` copied to `.env` and credentials changed.
- Jobs remain `QUEUED` until the next task adds the production consumer.
- RabbitMQ carries identifiers only, never archives or PII.
- Outbox publication is at-least-once.
- A broker outage does not invalidate an already accepted upload.
- A crash between MinIO upload and SQLite commit can leave an orphan object; reconciliation is deferred.
- Ports 9001 and 15672 are local-development management ports and must not be publicly exposed.

Do not document example commands containing real credentials.

## Logging and PII rules

Allowed at info/warn level:

```text
generated posRecordId
generated jobId
generated outbox eventId
state names
stable error category
attempt count
PDF count without filenames
byte counts
```

Forbidden in logs, metrics labels, exception responses, RabbitMQ headers/body, and outbox payload:

```text
original filename
ZIP entry filename
eRef
policy number
policyholder/consultant names
SHA-256
archive/PDF bytes
OCR text
MinIO credentials
RabbitMQ credentials
raw database/broker/storage exception messages
temporary file path
```

Generated UUID-only MinIO object keys may appear only in a warning about failed compensation/orphan recovery. Do not place them in normal request logs.

## Concurrency and idempotency rules

- SQLite Hikari maximum pool size remains `1`.
- Do not increase Rabbit listener concurrency because no production listener exists yet.
- The database unique indexes remain the final authority for eRef/policy uniqueness.
- Repository `exists` checks are advisory only.
- Each upload attempt creates a new set of UUIDs.
- A client retry after a successful but response-lost upload can receive `409`; request idempotency keys are deferred.
- The outbox relay may publish duplicates across a crash and must document at-least-once semantics.
- Never mark an event published before receiving a positive publisher confirm.
- Never mark an accepted job failed merely because RabbitMQ is temporarily down.

## Verification order

Run in this exact order:

```text
1. Existing Maven baseline before modifications
2. Maven clean verify after implementation
3. Maven clean verify again
4. Docker Compose configuration validation
5. Whole-stack verification script
6. Maven clean verify after the stack test
7. Repository cleanliness checks
```

Windows `cmd.exe` plus Git Bash for the shell script:

```bat
mvnw.cmd clean verify
mvnw.cmd clean verify
docker compose --env-file .env.example config --quiet
bash scripts/verify-container-stack.sh
mvnw.cmd clean verify
git status --short
```

POSIX shell:

```bash
./mvnw clean verify
./mvnw clean verify
docker compose --env-file .env.example config --quiet
./scripts/verify-container-stack.sh
./mvnw clean verify
git status --short
```

Inspect dependency resolution:

```text
Maven dependency tree for:
  org.springframework.amqp
  com.rabbitmq
  org.testcontainers:testcontainers-rabbitmq
```

Do not use:

- `-DskipTests`
- `testFailureIgnore`
- Test exclusions
- JUnit assumptions that silently skip Docker tests
- An embedded fake RabbitMQ broker as a substitute for the real integration test
- An in-memory database as a substitute for SQLite

## Acceptance criteria

All conditions must be true:

- The upload endpoint accepts a ZIP containing multiple PDFs.
- Actual compressed bytes are limited to 10 MiB.
- Entry count, per-entry bytes, total expanded bytes, compression ratio, paths, file suffixes, and PDF signatures are validated.
- ZIP/PDF bytes are streamed, not loaded wholesale into heap memory.
- The original ZIP is stored byte-for-byte in MinIO.
- MinIO object keys contain generated UUIDs only and no PII.
- Storage metadata, POS record, queued job, and outbox event commit together.
- A database failure after upload triggers scoped MinIO compensation.
- The outbox message contains identifiers only.
- RabbitMQ topology is durable and includes a DLQ.
- RabbitMQ publication requires a positive publisher confirm and successful routing.
- RabbitMQ downtime does not cause a durable upload to be lost or its job to be marked failed.
- Failed outbox publications retry with bounded backoff.
- Successful outbox events are not republished during normal relay runs.
- At-least-once crash semantics are documented.
- The real job GET endpoint returns the stored queued job.
- No production Rabbit consumer exists yet.
- No `pos_document` is created before actual extraction.
- Existing unrelated Task 1 dummy endpoints remain unchanged.
- Existing Task 2 MinIO/SQLite Docker behavior remains intact.
- Existing Task 3 migrations and repository tests remain intact.
- Maven, real MinIO, real RabbitMQ, and whole-stack tests all pass.
- No PII, credentials, ZIPs produced at runtime, database files, or container data are accidentally committed.

## Required final report

After implementation, report only:

1. Handwritten files created or changed.
2. Exact Spring AMQP, RabbitMQ Java client, Testcontainers, RabbitMQ image, MinIO SDK/image, Flyway, and SQLite JDBC versions resolved or used.
3. ZIP limits and validation rules implemented.
4. RabbitMQ exchange, queue, routing key, DLX, dead-letter queue, and retry schedule.
5. The exact message fields and confirmation/routing behavior.
6. Transaction and MinIO-compensation behavior.
7. Every verification command executed.
8. Final Maven test count, failures, errors, and skips.
9. Whether the whole-stack upload, queue persistence across RabbitMQ restart, MinIO persistence, SQLite persistence, and backend restart checks passed.
10. Any deviation from this document, with a concrete technical reason.

Do not claim success unless every verification command completed successfully.

## Stop conditions

Stop and ask the user instead of improvising if:

- The generated upload method cannot represent the required multipart request without changing OpenAPI.
- Implementing the limits requires buffering the entire ZIP or any large PDF in memory.
- The current `MinioObjectStorage` cannot stream a known-size file without changing its public contract incompatibly.
- Flyway V2 conflicts with committed production data.
- RabbitMQ publisher confirms or mandatory returns cannot be enabled with the project's managed Spring AMQP version.
- A RabbitMQ image tag does not exist; verify an exact official tag and report the proposed substitution before proceeding.
- A requirement appears to require a production consumer, PDF extraction, OCR, authentication, or fuzzy search.
- Any dependency requires changing Spring Boot, Java, Hibernate, SQLite, MinIO, or existing Testcontainers versions.
- The baseline tests fail before implementation starts.

