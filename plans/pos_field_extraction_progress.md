# Task: Structured POS field extraction (policyholder/consultant/submission-date)

Branch: `task-pos-field-extraction` (base `main`)

## Status legend
- [ ] not started
- [~] in progress
- [x] done

## Production code
- [x] V4__create_pos_field_extraction.sql
- [x] ExtractionField / ExtractionOutcome enums
- [x] PosFieldExtractionEntity + PosFieldExtractionId + PosFieldExtractionRepository
- [x] LlamaCppOcrProperties: maxAttempts + retryBackoffMs + validation
- [x] LlamaCppOcrClient.recognize(page, prompt, version); remove hard-coded prompt
- [x] Remove FirstPageOcrService (+ bean) — replaced by workflow
- [x] FieldExtractionPrompts (3 exact prompts + version 2)
- [x] FieldAnswerParser (normalize + name/date validation)
- [x] DocumentSnapshot + DocumentCandidateSelector (LAPPe/fallback rule)
- [x] ExtractionBackoff (injectable sleeper)
- [x] FieldExtractionPersistenceService (snapshots, field values, statuses, upsert, apply, completion)
- [x] StructuredFieldExtractionService (workflow)
- [x] IngestionConsumerService -> new workflow
- [x] OcrConfiguration wiring
- [x] application.yaml config

## Tests
- [x] Candidate-selection unit tests
- [x] Prompt + client tests (3 separate requests, render-once, retry reuse, no logs)
- [x] Parser tests
- [x] Retry-policy tests (exact request counts, in workflow test)
- [x] Persistence integration tests (real SQLite)
- [x] Workflow integration tests (real renderer, synthetic PDFs, stub) + e2e
- [x] RabbitMQ retry test updates (IngestionRetryIntegrationTest: best-effort OCR semantics)
- [x] Update/remove old-OCR tests (workflow, first-page deleted; client + properties updated)
- [x] Existing-test updates: IngestionConsumerIntegrationTest (SKIPPED), MigrationIntegrationTest (V4)

## Whole-stack + docs (remaining)
- [x] Fixture: `valid-with-lappe.zip` (LAPPe.pdf + other.pdf) used by the stack test
- [x] WireMock mapping: 3 field-specific mappings (matchesJsonPath on the exact prompt)
- [x] verify-container-stack.sh: 3 OCR requests, field assertions, dup stays 3
- [x] README/docs: LAPPe, 10-PDF fallback, 3 calls, best-effort null, worst case 90

## Verification
- [x] ./mvnw clean verify (run 1) — 680 tests, 0 failures
- [x] ./mvnw clean verify (run 2) — 680 tests, 0 failures
- [x] docker compose --env-file .env.example config --quiet
- [x] scripts/verify-container-stack.sh -> ALL CHECKS PASSED

## ChatGPT review fixes (PR #3 — 5 merge blockers)
- [x] **1. Candidate selection** — `DocumentCandidateSelector` now returns a `List<DocumentSnapshot>`: every case-sensitive `LAPPe.pdf` basename (sequence order), or the first up to 10 PDFs when none match. `ArchiveExtractionService.lastSegment()` now preserves case (was lowercasing), so the case-sensitive match is possible.
- [x] **2. Permanent render failure** — a non-retryable render failure marks the candidate `FAILED` (new `markDocumentFailed`) and continues to the next candidate; the job still completes `COMPLETED`. Temporary render failure and interruption escape as a retryable `ConsumerException`.
- [x] **3. Answer parser** — strict validation: rejects newlines (not collapsed), unmatched quotes, backticks, field labels (colon), list markers, "A or B" alternatives, and prose (>4 words / disallowed chars); requires a Unicode letter; restricts names to letters/spaces/apostrophes/hyphens; strips a trailing bracketed ID; `NOT FOUND`/`UNKNOWN`/etc. are `UNKNOWN` (not a name).
- [x] **4. Authoritative upsert** — `applyIfResolved` applies only the durable row's `value_text` (never a locally-proposed value), so a losing upsert caller cannot apply an unrecorded value.
- [x] **5. Retry/interruption classification** — `OCR_RESPONSE_INVALID`, `OCR_OUTPUT_EMPTY`, `OCR_OUTPUT_TRUNCATED` are now retryable (retried within the field); `OCR_INTERRUPTED` escapes to the RabbitMQ retry path (never persisted as `FAILED`) and preserves the interrupt flag.
- [x] `LlamaCppOcrClient` de-finalized (test double for interruption escaping; Mockito unavailable).
- [x] New/updated tests: multi-candidate sequential processing, case-sensitivity (exact match + >10 priority + lowercase decoy), corrupt-candidate→FAILED→continue, upsert-race reconciliation, malformed/empty/truncated retries, interruption escaping, `markDocumentFailed` + completion-with-FAILED, no-calls-when-resolved; parser test rewritten for strict validation.
- [x] `PosRecordCommandService` comment updated (FAILED is a terminal state that does not block verification).
- [x] README structured-field-extraction section rewritten for the multi-candidate + case-sensitive + render-failure semantics.
- [x] Full suite after round 1: `./mvnw -o test` — 700 tests, 0 failures, 0 errors.

## ChatGPT review round 2 (PR #3 — 5 more blockers)
- [x] **1. Backoff interruption** — `ExtractionBackoff.realTime()` now throws `ExtractionBackoffInterruptionException` (new); the workflow's `sleepUnlessLastAttempt` catches it and re-raises a retryable `ConsumerException(EXTRACTION_TRANSIENT_FAILURE)`, preserving the interrupt flag, so it flows through the consumer's retry / terminal-recovery path instead of dead-lettering without recovery.
- [x] **2. `UNKNOWN (123)` never a name** — the policyholder's trailing ID is removed first, then blank/length/sentinel/label/ambiguity/name validation re-run on the result (ID removal applied only to `POLICYHOLDER_NAME`); `UNKNOWN (123)` is `UNKNOWN`, never a resolved name.
- [x] **3. Name parsing** — removed the arbitrary four-word cap (legitimate long names accepted); added explicit field-label/prose-word detection (`name`, `policyholder`, `policyowner`, `consultant`, `submission`, `date`); trim leading/trailing whitespace (incl. a trailing newline) before the newline check so a trailing `\n` is valid while internal newlines stay invalid.
- [x] **4. Redelivery must not re-render / demote a candidate** — `processCandidate` reconciles durable outcomes before rendering; if every currently-unresolved field already has a durable outcome it marks/preserves `COMPLETED` and continues without rendering; terminal states (COMPLETED/FAILED/SKIPPED) are never demoted; the business field is refreshed before each OCR request so a concurrent human update prevents the corresponding call.
- [x] **5. `updatedAt` on field update** — `applyResolvedField` stamps `updatedAt` in the same transaction only when a null field is actually filled; a no-op (existing user value) changes neither timestamp nor version.
- [x] New tests: backoff-interruption escaping, `UNKNOWN (123)` sentinel, trailing-newline trim, long names, label/prose-without-colon, redelivery no-re-render of a COMPLETED candidate, user-update-between-requests, timestamp/version-only-on-update, and a real-broker partial-recovery scenario (transient render failure on the second candidate; first candidate not re-rendered).

## Final verification (consistent result)
- [x] `./mvnw -o clean verify` (run 1) — **711 tests, 0 failures, 0 errors**.
- [x] `./mvnw -o clean verify` (run 2) — **711 tests, 0 failures, 0 errors**.
- [x] `docker compose --env-file .env.example config --quiet` — OK.
- [x] `scripts/verify-container-stack.sh` — **ALL CHECKS PASSED** (job COMPLETED/attemptCount=1; candidate COMPLETED + non-candidate SKIPPED; exactly 3 OCR requests (one per field) and still 3 after duplicate delivery; 3 version-2 RESOLVED outcomes; field values Charlie Henry / John Davidson / 2026-07-26 applied).

## Deviations from spec
- Kept `DocumentOcrPersistenceService` + its integration tests (historical, for the retained `document_ocr_result` table); it is no longer on the consumer path.
- `IngestionRetryIntegrationTest` scenarios 6-7 (OCR-fails-the-job, don't-re-OCR-doc1) removed: obsolete under the best-effort single-candidate workflow; replaced by 503/400 best-effort scenarios.
- `PosRecordCommandService.meetsVerificationPrerequisites`: verification was blocked by any non-COMPLETED document. Under the new workflow non-candidates end SKIPPED (terminal), so the check now blocks only on in-progress states (PENDING/PROCESSING). The existing `verifyWithAnIncompleteDocumentReturns422` test uses PROCESSING and still passes.
- Whole-stack fixture is a separate file (`valid-with-lappe.zip`) so the existing `valid-two-pdf.zip` consumers (FixtureIntegrityTest, PosArchiveIntakeIntegrationTest) are unaffected.
