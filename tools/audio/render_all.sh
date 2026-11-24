#!/usr/bin/env bash
# Re-renders every Spindle Drop sound effect and music loop from source, encodes the
# music to AAC (.m4a) and verifies the results.
#
#   tools/audio/render_all.sh                      # everything
#   tools/audio/render_all.sh --only sfx_coin,music_menu
#
# Requirements: macOS (afconvert/afinfo) and Java 21+. Outputs go ONLY to
# app/src/main/res/raw/; intermediates go to tools/audio/build/ (git-ignored).
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
RAW="$ROOT/app/src/main/res/raw"
BUILD="$HERE/build"
JAVA="${JAVA:-java}"

command -v afconvert >/dev/null || { echo "afconvert (macOS) is required" >&2; exit 1; }
mkdir -p "$RAW" "$BUILD/music" "$BUILD/check" "$BUILD/classes"

# Compile once for speed; fall back to the single-file source launcher if javac is missing.
if command -v javac >/dev/null 2>&1; then
  javac -d "$BUILD/classes" "$HERE/SynthStudio.java"
  RUN=("$JAVA" -Xmx4g -cp "$BUILD/classes" SynthStudio)
else
  RUN=("$JAVA" -Xmx4g "$HERE/SynthStudio.java")
fi

echo "==> Rendering"
"${RUN[@]}" render --out "$RAW" --work "$BUILD" "$@"

echo
echo "==> Encoding music to AAC 160 kbps (.m4a)"
shopt -s nullglob
for wav in "$BUILD"/music/music_*.wav; do
  name="$(basename "$wav" .wav)"
  afconvert -f m4af -d aac -b 160000 "$wav" "$RAW/$name.m4a"
  # Decode again so the encoded result can be checked for level/clipping.
  afconvert -f WAVE -d LEI16 "$RAW/$name.m4a" "$BUILD/check/$name.decoded.wav"
  echo "    $name.m4a"
done

echo
echo "==> Verify: SFX in res/raw"
"${RUN[@]}" analyze "$RAW"/sfx_*.wav
echo
echo "==> Verify: music masters (loop seam) and decoded AAC"
"${RUN[@]}" analyze --loop "$BUILD"/music/music_*.wav "$BUILD"/check/music_*.decoded.wav

echo
echo "==> Verify: afinfo"
missing=0
while IFS= read -r f; do
  path="$RAW/$f"
  if [[ ! -f "$path" ]]; then echo "    MISSING $f"; missing=1; continue; fi
  info="$(afinfo "$path")"
  fmt="$(grep -m1 'Data format' <<<"$info" | sed 's/.*Data format: *//')"
  dur="$(grep -m1 'estimated duration' <<<"$info" | sed 's/.*duration: *//')"
  printf '    %-24s %-16s %s\n' "$f" "$dur" "$fmt"
done < <("${RUN[@]}" expected)

echo
echo "==> Total size of res/raw audio"
du -ch "$RAW"/sfx_*.wav "$RAW"/music_*.m4a | tail -1
if [[ $missing -ne 0 ]]; then echo "Some expected files are missing" >&2; exit 1; fi
