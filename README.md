# POS Document Backend

Spring Boot backend that ingests POS-archive ZIP uploads into durable storage
(MinIO), records the ingestion state in SQLite, and hands off processing work to
RabbitMQ through a transactional outbox.

## Local endpoints

| Service | Endpoint |
| --- | --- |
| Backend API | `http://localhost:8080/api/v1` |
| MinIO API | `http://localhost:9000` |
| MinIO console (local development only) | `http://localhost:9001` |
| RabbitMQ AMQP | `localhost:5672` |
| RabbitMQ management (local development only) | `http://localhost:15672` |

Ports `9001` and `15672` are **local-development management ports and must not
be publicly exposed**.

## Starting the stack

Copy `.env.example` to `.env` and change the credentials before starting the
stack:

```bat
copy .env.example .env
docker compose up --build
```

Do not commit real credentials, and do not put them in example commands.

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
docker compose --env-file .env.example config --quiet
bash scripts/verify-container-stack.sh
```
