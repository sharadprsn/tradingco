import sys
import pathlib

p = pathlib.Path(sys.argv[1])
content = p.read_bytes()

# Replace known Unicode chars with ASCII equivalents
replacements = {
    b'\xe2\x89\xa5': b'>=',  # ≥
    b'\xe2\x86\x92': b'->',  # →
}
for old, new in replacements.items():
    content = content.replace(old, new)

p.write_bytes(content)
print(f"Cleaned: {sys.argv[1]}")
