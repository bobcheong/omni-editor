#!/usr/bin/env bash
# T-02: Generate the golden test corpus for the diff engine.
# Run from the repository root. Committed fixtures go in testfixtures/golden/;
# the 300 MB generated file goes in testfixtures/golden/generated/ (gitignored).
set -euo pipefail

GOLDEN="testfixtures/golden"

# ── Helper ──
write_expected() {
    local dir="$1"
    shift
    # Arguments: pairs of "leftStart,leftEnd,rightStart,rightEnd,type"
    local json='{"hunks":['
    local first=true
    for hunk in "$@"; do
        IFS=',' read -r ls le rs re t <<< "$hunk"
        $first || json+=","
        first=false
        json+="{\"leftStart\":$ls,\"leftEnd\":$le,\"rightStart\":$rs,\"rightEnd\":$re,\"type\":\"$t\"}"
    done
    json+=']}'
    echo "$json" > "$dir/expected.json"
}

# ── 1. identical ──
cat > "$GOLDEN/identical/left" <<'EOF'
line one
line two
line three
EOF
cp "$GOLDEN/identical/left" "$GOLDEN/identical/right"
write_expected "$GOLDEN/identical"
# no hunks

# ── 2. single-line-change ──
cat > "$GOLDEN/single-line-change/left" <<'EOF'
alpha
beta
gamma
EOF
cat > "$GOLDEN/single-line-change/right" <<'EOF'
alpha
BETA
gamma
EOF
write_expected "$GOLDEN/single-line-change" "1,2,1,2,CHANGED"

# ── 3. insert-at-head ──
cat > "$GOLDEN/insert-at-head/left" <<'EOF'
first
second
third
EOF
cat > "$GOLDEN/insert-at-head/right" <<'EOF'
zeroth
first
second
third
EOF
write_expected "$GOLDEN/insert-at-head" "0,0,0,1,ADDED"

# ── 4. insert-at-tail ──
cat > "$GOLDEN/insert-at-tail/left" <<'EOF'
first
second
third
EOF
cat > "$GOLDEN/insert-at-tail/right" <<'EOF'
first
second
third
fourth
EOF
write_expected "$GOLDEN/insert-at-tail" "3,3,3,4,ADDED"

# ── 5. whole-file-replaced ──
cat > "$GOLDEN/whole-file-replaced/left" <<'EOF'
old line one
old line two
old line three
EOF
cat > "$GOLDEN/whole-file-replaced/right" <<'EOF'
new content A
new content B
new content C
new content D
EOF
write_expected "$GOLDEN/whole-file-replaced" "0,3,0,4,CHANGED"

# ── 6. crlf-vs-lf ──
# Left uses CRLF, right uses LF. Same textual content.
printf "line one\r\nline two\r\nline three\r\n" > "$GOLDEN/crlf-vs-lf/left"
printf "line one\nline two\nline three\n" > "$GOLDEN/crlf-vs-lf/right"
# With ignoreLineEndings=true (default), no hunks.
# With ignoreLineEndings=false, every line differs.
write_expected "$GOLDEN/crlf-vs-lf"
# expected.json has no hunks (default rules ignore line endings)

# ── 7. utf8-vs-utf16le-bom ──
# Same text, different encodings. Left is UTF-8, right is UTF-16LE with BOM.
printf "hello world\nline two\n" > "$GOLDEN/utf8-vs-utf16le-bom/left"
python3 -c "
import codecs
text = 'hello world\nline two\n'
with open('$GOLDEN/utf8-vs-utf16le-bom/right', 'wb') as f:
    f.write(codecs.BOM_UTF16_LE)
    f.write(text.encode('utf-16-le'))
"
write_expected "$GOLDEN/utf8-vs-utf16le-bom"
# After encoding detection and normalisation, content is identical — no hunks.

# ── 8. no-trailing-newline ──
printf "line one\nline two" > "$GOLDEN/no-trailing-newline/left"
printf "line one\nline two\n" > "$GOLDEN/no-trailing-newline/right"
# The last line differs (one has \n, one doesn't). With ignoreLineEndings=true, no hunks.
write_expected "$GOLDEN/no-trailing-newline"

# ── 9. empty-vs-nonempty ──
> "$GOLDEN/empty-vs-nonempty/left"
printf "content\n" > "$GOLDEN/empty-vs-nonempty/right"
write_expected "$GOLDEN/empty-vs-nonempty" "0,0,0,1,ADDED"

# ── 10. one-byte-files ──
printf "A" > "$GOLDEN/one-byte-files/left"
printf "B" > "$GOLDEN/one-byte-files/right"
write_expected "$GOLDEN/one-byte-files" "0,1,0,1,CHANGED"

# ── 11. large-identical-one-change ──
# 10000 lines, identical except line 5000 (0-indexed 4999).
python3 -c "
for i in range(10000):
    print(f'line {i:05d}: common content here')
" > "$GOLDEN/large-identical-one-change/left"
python3 -c "
for i in range(10000):
    if i == 4999:
        print(f'line {i:05d}: MODIFIED content here')
    else:
        print(f'line {i:05d}: common content here')
" > "$GOLDEN/large-identical-one-change/right"
write_expected "$GOLDEN/large-identical-one-change" "4999,5000,4999,5000,CHANGED"

# ── 12. reordered-blocks ──
cat > "$GOLDEN/reordered-blocks/left" <<'EOF'
block A line 1
block A line 2
block A line 3
block B line 1
block B line 2
block B line 3
block C line 1
block C line 2
block C line 3
EOF
cat > "$GOLDEN/reordered-blocks/right" <<'EOF'
block C line 1
block C line 2
block C line 3
block A line 1
block A line 2
block A line 3
block B line 1
block B line 2
block B line 3
EOF
# Block C moved from end to beginning. The exact hunks depend on the algorithm,
# but at minimum there will be removals and additions.
write_expected "$GOLDEN/reordered-blocks" \
    "0,3,0,0,REMOVED" \
    "6,9,3,3,REMOVED" \
    "9,9,3,6,ADDED" \
    "9,9,6,9,ADDED"
# Note: actual hunks will be verified/adjusted when the engine is implemented.
# This is a reference expectation — the engine may produce equivalent but different hunks.

# ── 13. whitespace-only-diff ──
cat > "$GOLDEN/whitespace-only-diff/left" <<'EOF'
no change
  leading spaces
trailing spaces
	tab indented
EOF
cat > "$GOLDEN/whitespace-only-diff/right" <<'EOF'
no change
    leading spaces
trailing spaces
		tab indented
EOF
# With ignoreWhitespace=ALL, no hunks. Without, lines 1-3 differ.
write_expected "$GOLDEN/whitespace-only-diff" \
    "1,2,1,2,CHANGED" \
    "2,3,2,3,CHANGED" \
    "3,4,3,4,CHANGED"

# ── 14. case-only-diff ──
cat > "$GOLDEN/case-only-diff/left" <<'EOF'
Hello World
foo bar
CONSTANT_VALUE
EOF
cat > "$GOLDEN/case-only-diff/right" <<'EOF'
hello world
FOO BAR
CONSTANT_VALUE
EOF
# With ignoreCase=true, no hunks. Without, lines 0-1 differ.
write_expected "$GOLDEN/case-only-diff" \
    "0,1,0,1,CHANGED" \
    "1,2,1,2,CHANGED"

# ── 15. minified-long-line ──
# A single line ~400KB long (minified JS), with a small change in the middle.
python3 -c "
import random
random.seed(42)
chars = 'abcdefghijklmnopqrstuvwxyz0123456789_.,;:{}[]()!='
line = ''.join(random.choice(chars) for _ in range(400000))
print(line)
" > "$GOLDEN/minified-long-line/left"
python3 -c "
import random
random.seed(42)
chars = 'abcdefghijklmnopqrstuvwxyz0123456789_.,;:{}[]()!='
line = list(''.join(random.choice(chars) for _ in range(400000)))
# Change 10 characters at position 200000
for i in range(10):
    line[200000 + i] = 'X'
print(''.join(line))
" > "$GOLDEN/minified-long-line/right"
write_expected "$GOLDEN/minified-long-line" "0,1,0,1,CHANGED"

echo "Golden corpus generated ($(find "$GOLDEN" -type f | wc -l) files)."
echo "Run tools/generate-large-fixture.sh separately for the 300 MB test file."
