Meshtastic protocol buffer definitions, vendored from

    https://github.com/meshtastic/protobufs
    commit 8f97d66a63ce10cfb12f94203e361691647f5ad3

Copied unmodified. The Gradle protobuf plugin compiles them into
`org.meshtastic.proto.*` with the javalite runtime at build time.

Pinned deliberately, in `firmware/daemon-pins.properties` alongside the
Meshtastic daemon commit. Bump both together: the wire format the daemon speaks
comes from the firmware's copy of these files, so a mismatched pair can make a
field silently stop decoding.

Only `.proto` files are vendored. The `.options` files in the upstream repo are
nanopb sizing hints used by the firmware build and have no effect here.

`nanopb.proto` is not in this directory. It lives in `../protoinclude`, which is on
protoc's include path but is not compiled; that file's README explains why.
