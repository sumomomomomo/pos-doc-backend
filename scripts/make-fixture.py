"""Regenerate the committed whole-stack test fixture and verify its hash.

The fixture at ``src/test/resources/fixtures/valid-two-pdf.zip`` is a tiny,
deliberately synthetic ZIP containing exactly two dummy PDFs:

    documents/first.pdf
    documents/second.pdf

It is built with fixed entry timestamps and DEFLATE so the output is
byte-stable across machines. The committed fixture's expected SHA-256 is
asserted at the bottom of this script so any accidental (or malicious)
replacement is detected immediately.

Usage (from the repository root):

    python scripts/make-fixture.py
"""
import hashlib
import os
import sys
import zipfile

# The committed fixture must produce exactly this SHA-256.
EXPECTED_SHA256 = "1ce96e72137fd1b084410d8f1f9154bce9dfd435fc9e9ab6a8ea340968e362a0"

# Minimal synthetic PDF bytes; validation only checks the leading magic.
PDF = b'%PDF-1.4\n% dummy test document\n%%EOF\n'

# Resolve the fixture path relative to this script so it works from any CWD.
REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(REPO_ROOT, 'src', 'test', 'resources', 'fixtures', 'valid-two-pdf.zip')


def build(path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    # Deterministic: fixed timestamps and explicit DEFLATE, so the committed
    # fixture is byte-stable across machines.
    with zipfile.ZipFile(path, 'w', zipfile.ZIP_DEFLATED) as z:
        info1 = zipfile.ZipInfo('documents/first.pdf', date_time=(2026, 1, 1, 0, 0, 0))
        info1.compress_type = zipfile.ZIP_DEFLATED
        z.writestr(info1, PDF)
        info2 = zipfile.ZipInfo('documents/second.pdf', date_time=(2026, 1, 1, 0, 0, 0))
        info2.compress_type = zipfile.ZIP_DEFLATED
        z.writestr(info2, PDF)


def sha256_of(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(65536), b''):
            h.update(chunk)
    return h.hexdigest()


def main():
    build(OUT)
    actual = sha256_of(OUT)
    size = os.path.getsize(OUT)
    print('SHA256', actual)
    print('SIZE', size)
    if actual != EXPECTED_SHA256:
        print('ERROR: generated fixture hash does not match the expected fixture hash.')
        print('  expected:', EXPECTED_SHA256)
        print('  actual:  ', actual)
        print('The committed fixture is unchanged on disk only if you re-verify after '
              'restoring the original; do not commit a differing fixture.')
        sys.exit(1)
    print('OK: fixture hash matches the committed fixture.')


if __name__ == '__main__':
    main()
