#!/usr/bin/env bash
# T-02: Generate the 300 MB log file with 12 known changes.
# This is local-only (testfixtures/golden/generated/ is gitignored).
# Takes ~30s and ~600 MB disk (left + right).
set -euo pipefail

OUT="testfixtures/golden/generated/large-log"
mkdir -p "$OUT"

echo "Generating 300 MB log files with 12 known changes..."

python3 -c "
import hashlib

TOTAL_LINES = 3_000_000  # ~100 bytes/line → ~300 MB
# 12 changes at evenly spaced positions
CHANGE_LINES = sorted([
    250_000, 500_000, 750_000, 1_000_000,
    1_250_000, 1_500_000, 1_750_000, 2_000_000,
    2_250_000, 2_500_000, 2_750_000, 2_900_000
])

change_set = set(CHANGE_LINES)
changes_json = []

with open('$OUT/left', 'w') as left, open('$OUT/right', 'w') as right:
    for i in range(TOTAL_LINES):
        ts = f'2026-01-15T{(i // 3600) % 24:02d}:{(i // 60) % 60:02d}:{i % 60:02d}.{i % 1000:03d}Z'
        base = f'{ts} [INFO] worker-{i % 16:02d} request_id={i:08x} processed batch item={i} status=ok'
        if i in change_set:
            left.write(base + '\n')
            modified = f'{ts} [WARN] worker-{i % 16:02d} request_id={i:08x} processed batch item={i} status=degraded latency=high'
            right.write(modified + '\n')
            changes_json.append({'line': i, 'type': 'CHANGED'})
        else:
            left.write(base + '\n')
            right.write(base + '\n')

# Write expected hunks
import json
hunks = [{'leftStart': c['line'], 'leftEnd': c['line']+1,
          'rightStart': c['line'], 'rightEnd': c['line']+1,
          'type': c['type']} for c in changes_json]
with open('$OUT/expected.json', 'w') as f:
    json.dump({'hunks': hunks}, f, indent=2)

print(f'Generated {TOTAL_LINES} lines, {len(changes_json)} changes')
"

left_size=$(stat --format=%s "$OUT/left" 2>/dev/null || stat -f%z "$OUT/left")
right_size=$(stat --format=%s "$OUT/right" 2>/dev/null || stat -f%z "$OUT/right")
echo "Left:  $(echo "scale=1; $left_size / 1048576" | bc) MB"
echo "Right: $(echo "scale=1; $right_size / 1048576" | bc) MB"
echo "Done. Files in $OUT/"
