#!/usr/bin/env bash
# Sync the app's vendored Meshtastic schemas from the firmware's pinned submodule.
# field_metadata.proto is include-only because its descriptor extensions need
# DescriptorProtos, which protobuf-javalite does not include.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
. "$ROOT/firmware/daemon-pins.properties"
FW="${1:?Usage: sync-meshtastic-protos.sh /path/to/meshtastic-firmware-checkout}"
PROTO_SRC="$FW/protobufs"
PROTO_DST="$ROOT/app/src/main/proto/meshtastic"
INCLUDE_DST="$ROOT/app/src/main/protoinclude"

[ -d "$PROTO_SRC/.git" ] || [ -f "$PROTO_SRC/.git" ] || {
  echo "!! $PROTO_SRC is not an initialized protobufs submodule" >&2
  exit 1
}
FW_ACTUAL=$(git -C "$FW" rev-parse HEAD)
if [ "$FW_ACTUAL" != "$MESHTASTIC_COMMIT" ]; then
  echo "!! firmware checkout is $FW_ACTUAL, expected $MESHTASTIC_COMMIT from daemon-pins.properties" >&2
  exit 1
fi
ACTUAL=$(git -C "$PROTO_SRC" rev-parse HEAD)
if [ "$ACTUAL" != "$PROTOBUF_COMMIT" ]; then
  echo "!! protobufs submodule is $ACTUAL, expected $PROTOBUF_COMMIT from daemon-pins.properties" >&2
  exit 1
fi

mkdir -p "$PROTO_DST" "$INCLUDE_DST/meshtastic"
# Remove stale generated-source inputs, then copy only protocol definitions.
find "$PROTO_DST" -maxdepth 1 -type f -name '*.proto' -delete
find "$PROTO_SRC/meshtastic" -maxdepth 1 -type f -name '*.proto' \
  ! -name 'field_metadata.proto' -exec cp -t "$PROTO_DST" {} +
cp "$PROTO_SRC/nanopb.proto" "$INCLUDE_DST/nanopb.proto"
if [ -f "$PROTO_SRC/meshtastic/field_metadata.proto" ]; then
  cp "$PROTO_SRC/meshtastic/field_metadata.proto" "$INCLUDE_DST/meshtastic/field_metadata.proto"
else
  rm -f "$INCLUDE_DST/meshtastic/field_metadata.proto"
fi

# Keep the source note synchronized with the pin manifest. Refuse an unexpected
# README shape rather than editing an arbitrary matching hash.
README="$ROOT/app/src/main/proto/README.md"
python3 - "$README" "$PROTOBUF_COMMIT" <<'PY'
import re
import sys
from pathlib import Path

readme = Path(sys.argv[1])
commit = sys.argv[2]
text = readme.read_text()
pattern = re.compile(r"(?m)^    commit [0-9a-f]{40}$")
if len(pattern.findall(text)) != 1:
    raise SystemExit(f"!! expected one pinned commit line in {readme}; update it manually")
readme.write_text(pattern.sub(f"    commit {commit}", text))
PY
echo ">> Synced Meshtastic protobufs from $PROTOBUF_COMMIT"
