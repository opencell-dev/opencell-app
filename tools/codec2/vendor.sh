#!/usr/bin/env bash
# Vendors the Codec2 speech codec (drowe67/codec2, LGPL-2.1) into
# codec2/src/main/cpp/codec2 at a pinned commit: only the vocoder (no FreeDV
# modems), its headers, the codebooks generated from their text sources, a
# version.h, the licence and a note saying what was taken. Run from anywhere;
# needs git, a host C compiler and network access. Re-running it reproduces
# the same files, so a diff after a run means upstream or this script changed.
set -euo pipefail
export LC_ALL=C # stable sort order in README.opencell

COMMIT=310777b1c6f1af0bc7c72f5b32f80f6fd9136962 # drowe67/codec2 main, 2026-03-17
REPO=https://github.com/drowe67/codec2.git
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
DEST=$ROOT/codec2/src/main/cpp/codec2
WORK=${XDG_CACHE_HOME:-$HOME/.cache}/opencell-codec2-vendor

# The vocoder's sources (upstream src/CMakeLists.txt CODEC2_SRCS, less the modems).
SOURCES=(codec2.c codec2_fft.c kiss_fft.c kiss_fftr.c dump.c lpc.c nlp.c postfilter.c
         sine.c interp.c lsp.c mbest.c newamp1.c phase.c quantise.c pack.c)

# Generated codebooks: output name, table name, text sources (upstream src/CMakeLists.txt).
CODEBOOKS=(
  "codebook.c lsp_cb codebook/lsp1.txt codebook/lsp2.txt codebook/lsp3.txt codebook/lsp4.txt codebook/lsp5.txt codebook/lsp6.txt codebook/lsp7.txt codebook/lsp8.txt codebook/lsp9.txt codebook/lsp10.txt"
  "codebookd.c lsp_cbd codebook/dlsp1.txt codebook/dlsp2.txt codebook/dlsp3.txt codebook/dlsp4.txt codebook/dlsp5.txt codebook/dlsp6.txt codebook/dlsp7.txt codebook/dlsp8.txt codebook/dlsp9.txt codebook/dlsp10.txt"
  "codebookjmv.c lsp_cbjmv codebook/lspjmv1.txt codebook/lspjmv2.txt codebook/lspjmv3.txt"
  "codebookge.c ge_cb codebook/gecb.txt"
  "codebooknewamp1.c newamp1vq_cb codebook/train_120_1.txt codebook/train_120_2.txt"
  "codebooknewamp1_energy.c newamp1_energy_cb codebook/newamp1_energy_q.txt"
)

rm -rf "$WORK"
git clone -q --filter=blob:none "$REPO" "$WORK"
git -C "$WORK" checkout -q "$COMMIT"

rm -rf "$DEST"
mkdir -p "$DEST/src" "$DEST/include/codec2"
cp "$WORK/COPYING" "$DEST/COPYING"
for f in "${SOURCES[@]}"; do cp "$WORK/src/$f" "$DEST/src/"; done

sed -e 's/@CODEC2_VERSION_MAJOR@/1/' -e 's/@CODEC2_VERSION_MINOR@/2/' \
    -e 's/#cmakedefine CODEC2_VERSION_PATCH @CODEC2_VERSION_PATCH@/#define CODEC2_VERSION_PATCH 0/' \
    -e 's/@CODEC2_VERSION@/1.2.0/' "$WORK/cmake/version.h.in" > "$DEST/include/codec2/version.h"

cc -O2 -o "$WORK/generate_codebook" "$WORK/src/generate_codebook.c" -lm
for spec in "${CODEBOOKS[@]}"; do
  read -r out table inputs <<<"$spec"
  # shellcheck disable=SC2086 # inputs is a list
  (cd "$WORK/src" && "$WORK/generate_codebook" "$table" $inputs) > "$DEST/src/$out"
done

# Exactly the headers the sources include.
for f in "$DEST"/src/*.c; do
  cc -MM -I"$WORK/src" -I"$DEST/include" "$f"
done | tr ' \\' '\n\n' | grep "^$WORK/src/.*\.h$" | sort -u | while read -r h; do cp "$h" "$DEST/src/"; done

{
  echo "Codec2 vocoder vendored by tools/codec2/vendor.sh. Do not edit: re-run the script."
  echo
  echo "Upstream:  $REPO"
  echo "Commit:    $COMMIT"
  echo "Licence:   GNU LGPL 2.1 (COPYING). OpenCell links it as its own shared library,"
  echo "           libcodec2.so, so it can be replaced (LGPL 2.1 section 6(b))."
  echo "Generated: src/codebook*.c by upstream's generate_codebook from src/codebook/*.txt;"
  echo "           include/codec2/version.h from cmake/version.h.in (1.2.0)."
  echo
  echo "Files:"
  (cd "$DEST" && find . -type f ! -name README.opencell | sort | sed 's|^\./|  |')
} > "$DEST/README.opencell"
echo "vendored $(find "$DEST" -type f | wc -l) files into $DEST"
