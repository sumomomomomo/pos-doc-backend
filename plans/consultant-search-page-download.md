# Consultant search and page PDF download

Approved implementation design is recorded in the feature specification at
`../../_bmad-output/implementation-artifacts/spec-consultant-search-page-pdf-download.md`.

Add the consultant filter to the generated search contract and compare the
normalized existing consultant value at query time. Keep all supplied filters
conjunctive. A reviewer-only CSRF-protected POST accepts the displayed page's
ordered IDs, validates each active record and eRef, and builds a bounded ZIP in
a dedicated temporary directory from protected PDF descriptors. Complete the
archive before writing the response, and delete the file after transfer or
failure. On startup, remove archives left by stopped processes. The UI submits
the IDs from the settled page.
