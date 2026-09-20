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

## Deviations from spec
- Kept `DocumentOcrPersistenceService` + its integration tests (historical, for the retained `document_ocr_result` table); it is no longer on the consumer path.
- `IngestionRetryIntegrationTest` scenarios 6-7 (OCR-fails-the-job, don't-re-OCR-doc1) removed: obsolete under the best-effort single-candidate workflow; replaced by 503/400 best-effort scenarios.
- `PosRecordCommandService.meetsVerificationPrerequisites`: verification was blocked by any non-COMPLETED document. Under the new workflow non-candidates end SKIPPED (terminal), so the check now blocks only on in-progress states (PENDING/PROCESSING). The existing `verifyWithAnIncompleteDocumentReturns422` test uses PROCESSING and still passes.
- Whole-stack fixture is a separate file (`valid-with-lappe.zip`) so the existing `valid-two-pdf.zip` consumers (FixtureIntegrityTest, PosArchiveIntakeIntegrationTest) are unaffected.
