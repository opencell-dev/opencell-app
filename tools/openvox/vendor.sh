#!/usr/bin/env bash
# Vendors OpenVoxNB (oc_vox: OpenCell's own speech codec, Apache-2.0) into
# openvox/src/main/cpp/oc_vox at a pinned opencell-firmware commit. The core
# builds the same commit from its firmware submodule: move both pins together
# (OpenVoxNB spec V4). Only the production, fixed-point sources are taken (the
# component's OC_VOX_SRCS, checked against its CMakeLists.txt) with exactly the
# headers they include; the floating-point reference (ref/, oc_vox_ref*), the
# host tests, the tools and the C++ wrapper stay behind. Also writes
# oc_vox_vendor.h (the commit, for the JNI glue), OpenVoxVendor.kt
# (OC_VOX_VERSION and the commit, for Kotlin) and README.opencell.
# Run from anywhere; needs git and a host C compiler. It reads the pinned commit
# from a LOCAL opencell-firmware clone (git archive; no network, no remote):
# the first argument, else $OC_FIRMWARE_REPO, else the default below. Re-running
# it reproduces the same files, so a diff after a run means the pin or this
# script changed.
set -euo pipefail
export LC_ALL=C # stable sort order in README.opencell

COMMIT=52a84c9d558fc433b5680bc45fb5af6267ae0d94 # opencell-firmware codec-negotiation: oc_vox, no left shift of a negative value
REPO=${1:-${OC_FIRMWARE_REPO:-/home/devin/Documents/opencell/firmware}}
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
DEST=$ROOT/openvox/src/main/cpp/oc_vox
KT=$ROOT/openvox/src/main/kotlin/org/opencell/openvox/OpenVoxVendor.kt
WORK=${XDG_CACHE_HOME:-$HOME/.cache}/opencell-openvox-vendor
COMP=firmware/components/oc_vox

# The production sources, as the component's CMakeLists.txt lists them in OC_VOX_SRCS.
SOURCES=(oc_vox_modes.c oc_vox_layout.c oc_vox_bits.c oc_vox_fec.c oc_vox_quant.c oc_vox_cb.c
         oc_vox_conceal.c oc_vox_pipe.c fx/oc_vox_fx_tables.c fx/oc_vox_fx_dsp.c
         fx/oc_vox_fx_analysis.c fx/oc_vox_fx_synth.c oc_vox.c)

if ! git -C "$REPO" cat-file -e "$COMMIT^{commit}" 2>/dev/null; then
  echo "$REPO has no commit $COMMIT: pass an opencell-firmware clone that has it" >&2
  exit 1
fi
rm -rf "$WORK"
mkdir -p "$WORK"
git -C "$REPO" archive "$COMMIT" LICENSE "$COMP" | tar -x -C "$WORK"
SRC=$WORK/$COMP

listed=$(sed -n '/^set(OC_VOX_SRCS /,/)/p' "$SRC/CMakeLists.txt" | tr -s ' )' '\n\n' | grep '\.c$' | sort)
if [ "$listed" != "$(printf '%s\n' "${SOURCES[@]}" | sort)" ]; then
  echo "OC_VOX_SRCS at $COMMIT is not this script's SOURCES; update SOURCES:" >&2
  echo "$listed" >&2
  exit 1
fi

rm -rf "$DEST"
mkdir -p "$DEST/fx"
cp "$WORK/LICENSE" "$DEST/LICENSE"
for f in "${SOURCES[@]}"; do cp "$SRC/$f" "$DEST/$f"; done
# Exactly the headers the sources include, each at its place in the component (include/, the root, fx/).
for f in "${SOURCES[@]}"; do
  cc -std=c11 -MM -I"$SRC/include" -I"$SRC" -I"$SRC/fx" "$SRC/$f"
done | tr ' \\' '\n\n' | grep '\.h$' | sort -u | while read -r h; do
  rel=${h#"$SRC"/}
  mkdir -p "$DEST/$(dirname "$rel")"
  cp "$h" "$DEST/$rel"
done

VERSION=$(sed -n 's/^#define OC_VOX_VERSION *\([0-9][0-9]*\)u.*/\1/p' "$DEST/include/oc_vox.h")
if [ -z "$VERSION" ]; then
  echo "no OC_VOX_VERSION in oc_vox.h at $COMMIT" >&2
  exit 1
fi

cat > "$DEST/oc_vox_vendor.h" <<EOF
/* Written by tools/openvox/vendor.sh: the opencell-firmware commit these sources come from. */
#ifndef OC_VOX_VENDOR_H
#define OC_VOX_VENDOR_H
#define OC_VOX_VENDOR_COMMIT "$COMMIT"
#endif
EOF

mkdir -p "$(dirname "$KT")"
cat > "$KT" <<EOF
package org.opencell.openvox

/** Written by tools/openvox/vendor.sh: which oc_vox src/main/cpp/oc_vox holds (OpenVoxNB spec V4). Do not edit. */
object OpenVoxVendor {
    /** OC_VOX_VERSION: 0 until the bitstream freezes, and while it is 0 the codec is experimental (spec V3). */
    const val VERSION = $VERSION

    /** The opencell-firmware commit the sources come from. */
    const val COMMIT = "$COMMIT"
}
EOF

{
  echo "OpenVoxNB (oc_vox) vendored by tools/openvox/vendor.sh. Do not edit: re-run the script."
  echo
  echo "Upstream:  opencell-firmware ($COMP)"
  echo "Commit:    $COMMIT"
  echo "Version:   OC_VOX_VERSION $VERSION"
  echo "Licence:   Apache License 2.0 (LICENSE), like the rest of OpenCell."
  echo "Taken:     the production (fixed-point) sources, OC_VOX_SRCS in the component's"
  echo "           CMakeLists.txt, and the headers they include. Left behind: the float"
  echo "           reference (ref/, oc_vox_ref*), host tests, tools and the C++ wrapper."
  echo "Written:   oc_vox_vendor.h (the commit) and, outside this directory,"
  echo "           src/main/kotlin/org/opencell/openvox/OpenVoxVendor.kt."
  echo
  echo "Files:"
  (cd "$DEST" && find . -type f ! -name README.opencell | sort | sed 's|^\./|  |')
} > "$DEST/README.opencell"

echo "vendored $(find "$DEST" -type f | wc -l) files into $DEST (OC_VOX_VERSION $VERSION)"
