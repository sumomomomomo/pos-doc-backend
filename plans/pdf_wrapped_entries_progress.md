# Plan: Accept Java-serialized `byte[]` PDF entries in ZIP archives

## Context

Some clients encode each PDF ZIP entry as a Java-serialized `byte[]`
(`ObjectOutputStream.writeObject(byte[])`) rather than the raw PDF bytes.
Previously the backend accepted only raw `%PDF-` entries, so these archives
failed validation. This plan adds support for **both** forms.

## Design decisions

### 1. Shared decoder: `PdfEntryDecoder` (not a Spring bean)
A **plain, stateless `final` class** in the `archive` package. Both
`ZipArchiveValidator` and `ArchiveExtractionService` instantiate it with
`new PdfEntryDecoder()`. It is deliberately **not** a Spring component so that
the existing constructor signatures (and their tests) are unchanged.

### 2. Envelope format (fixed, no version field)
```
AC ED 00 05 75 72 00 02 5B 42 AC F3 17 F8 06 08 54 E0 02 00 00 78 70  <23 bytes>
<4-byte signed big-endian length>                                        <bytes 23-26>
<pdf payload>                                                            <from byte 27>
```
The decoder:
- Reads the 23-byte prefix and compares byte-for-byte.
- If it matches, reads the 4-byte length, validates
  `0 < length <= maxEntryBytes - 27`, reads exactly `length` bytes, and
  **writes only the normalized payload** to the caller's output stream.
- Otherwise (raw form) it copies at most `maxEntryBytes` bytes and rejects any
  trailing byte.
- Reports `sourceBytesRead` (raw entry length, **including** the 27-byte
  wrapper) and `pdfBytesWritten` (normalized length) so callers can account for
  limits and byte size correctly.
- Throws `ArchiveValidationException(INVALID_ARCHIVE)` for every
  structural/validation failure and `IOException` for I/O errors.

### 3. `ZipArchiveValidator` — validation only
`readAndValidatePdf` delegates to the decoder with
`OutputStream.nullOutputStream()`. It keeps only the raw expanded-byte count
(`sourceBytesRead`). The raw magic check was removed; `BUFFER_SIZE` (8192) is
retained because tests reference it. `readNonPdfEntry` (non-`.pdf` files) is
unchanged.

### 4. `ArchiveExtractionService` — decode + store in one pass
`streamNormalizedPdf` wraps the temp-file output in a `DigestOutputStream`, so
the **SHA-256 and byte size are computed over the normalized PDF in a single
pass** (the old `sha256OfFile` temp-file re-read is removed). The service:
- stores the normalized PDF bytes,
- records `byteSize = pdfBytesWritten` and `sha256` over the normalized bytes,
- accounts cumulative security limits with `sourceBytesRead` (raw, including
  wrappers) — passed both to the validator's `effectiveEntryLimit` and to the
  cumulative `maxUncompressedBytes` check.

### 5. Raw ZIP object is never modified
The original archive object in MinIO is stored verbatim at upload time and the
consumer only reads it; the extracted document objects are the only normalized
artifacts. The wrapper bytes never leak into any stored document.

## Tests added

- `PdfEntryDecoderTest` — 16 unit tests (raw, wrapped, every malformed case,
  raw-only, oversized raw).
- `ZipArchiveValidatorTest` — 4 new tests (mixed valid, uncompressed totals
  count wrappers, malformed wrapped rejects, raw-only valid).
- `ArchiveExtractionServiceIntegrationTest` — 3 new tests (mixed raw/wrapped
  produce normalized objects; raw expanded totals count wrappers for limits;
  idempotency with wrapped entries).
- `ArchiveExtractionServiceCompensationTest` — 1 new test (wrapped-entry upload
  failure compensates only the newly-created object).
- `WrappedPdfEndToEndIntegrationTest` — full intake → consumer → PDFBox end-to
  end, covering **both candidate variants** (wrapped `LAPPe.pdf` and raw
  `LAPPe.pdf`); asserts document statuses, normalized objects, PDFBox-loadable
  candidate, and a byte-for-byte identical archive that still contains the
  wrappers.

## Verification

- `./mvnw -o clean verify` (twice) — full suite green (768 tests after the
  review fixes).
- `docker compose --env-file .env.example config --quiet` — exit 0.
- `scripts/verify-container-stack.sh` — **ALL CHECKS PASSED** (full compose
  stack: health, OCR via WireMock, SQLite/MinIO persistence, idempotency,
  content endpoints, soft delete).
- No CI/GitHub Actions added. No credentials or real subject IDs in tracked
  files.

## Review fixes (post-first-commit)

### 1. Enforce cumulative limits *during* streaming in extraction
`ArchiveExtractionService` previously passed only `maxEntryBytes()` to the
decoder and checked the total/ratio limits only after the entry was written to
the temp file. Now, before decoding each entry, it computes the **remaining
effective raw-byte allowance** by reusing the validator's pre-entry arithmetic
(`ZipArchiveValidator.effectiveEntryLimit`, now `public` — single source of
truth: min of per-entry cap, remaining total-uncompressed, remaining
compression-ratio). That allowance is passed to `PdfEntryDecoder.decode()`, so
an entry that would breach a cumulative limit is rejected at the first
over-limit chunk, not after the whole entry hits the temp file. The post-stream
total/ratio checks are retained as defense-in-depth.

`ZipArchiveValidator.effectiveEntryLimit` was made `public` (widening
visibility only) so both the validator and the extraction service share the
exact same arithmetic.

New `ArchiveExtractionServiceEffectiveAllowanceTest` (mocked storage + a
`RecordingPdfEntryDecoder`) proves the service passes the *remaining* total and
the *remaining* ratio allowance to the decoder for a later entry (strictly
below `maxEntryBytes`), not just the static per-entry cap. `PdfEntryDecoder` is
de-finalized so the recording double can extend it (consistent with the
`LlamaCppOcrClient` test double). The extra 4-arg `ArchiveExtractionService`
constructor (decoder-injectable) is used only by tests; the production
3-arg constructor is `@Autowired`.

### 2. End-to-end test now goes through the public upload/intake path
`WrappedPdfEndToEndIntegrationTest` no longer inserts the ZIP into MinIO and
the rows by hand. It now submits the ZIP through the `/pos-records` upload
endpoint (MockMvc → `PosArchiveIntakeService` → `ZipArchiveValidator` — the
path that originally rejected the wrapped form), then publishes the outbox event
via the real `OutboxRelay` and lets the real consumer process it. It verifies
both preservation (the archive object is byte-for-byte the upload and still
carries the wrappers) and processing (candidate COMPLETED, non-candidates
SKIPPED, normalized objects, PDFBox-loadable candidate, record REVIEW_REQUIRED).

### Minor cleanup
- Decoder comment: byte `0x78` is `TC_ENDBLOCKDATA` and byte `0x70` is `TC_NULL`
  (was mislabeled `classDescEnd` / `TC_BLOCKDATALONG`).
- `ENVELOPE_PREFIX_LEN` is now used: `ENVELOPE_HEADER_LEN = ENVELOPE_PREFIX_LEN + 4`.
