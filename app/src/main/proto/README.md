Meshtastic protocol buffer definitions, vendored from

    https://github.com/meshtastic/protobufs
    commit ca2cb1a80fb7ef0e181a67877f71e37885f914cd

Copied unmodified. The Gradle protobuf plugin compiles them into
`org.meshtastic.proto.*` with the javalite runtime at build time.

Pinned deliberately, the same way `firmware/build-meshtasticd-android.sh` pins the
firmware commit. Bump both together: the wire format the daemon speaks comes from
the firmware's copy of these files, so a mismatched pair is how a field silently
stops decoding. `PROTO_COMMIT` in `docs/BUILDING.md` records the pairing.

Only `.proto` files are vendored. The `.options` files in the upstream repo are
nanopb sizing hints used by the firmware build and have no effect here.

`nanopb.proto` is not in this directory. It lives in `../protoinclude`, which is on
protoc's include path but is not compiled; that file's README explains why.
