import zipfile, hashlib, os

pdf = b'%PDF-1.4\n% dummy test document\n%%EOF\n'
os.makedirs('src/test/resources/fixtures', exist_ok=True)
out = 'src/test/resources/fixtures/valid-two-pdf.zip'

# Deterministic: fixed timestamps and no extra fields, so the committed
# fixture is byte-stable across machines.
with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as z:
    info1 = zipfile.ZipInfo('documents/first.pdf', date_time=(2026, 1, 1, 0, 0, 0))
    info1.compress_type = zipfile.ZIP_DEFLATED
    z.writestr(info1, pdf)
    info2 = zipfile.ZipInfo('documents/second.pdf', date_time=(2026, 1, 1, 0, 0, 0))
    info2.compress_type = zipfile.ZIP_DEFLATED
    z.writestr(info2, pdf)

h = hashlib.sha256(open(out, 'rb').read()).hexdigest()
print('SHA256', h)
print('SIZE', os.path.getsize(out))
