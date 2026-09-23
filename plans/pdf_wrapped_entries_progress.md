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

- `./mvnw -o clean verify` (twice) — full suite green.
- `docker compose --env-file .env.example config --quiet` — exit 0.
- No CI/GitHub Actions added. No credentials or real subject IDs in tracked
  files.
