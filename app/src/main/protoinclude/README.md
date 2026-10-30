# Protobuf include-only files

These schemas resolve imports during protoc generation but are not themselves
compiled into Java. The Gradle protobuf task adds this directory as an include
path. See `app/build.gradle.kts` for the configuration.

- `nanopb.proto` comes from `meshtastic/protobufs` at the pinned protobuf
  revision. It declares options extending the standard descriptor messages.
- `meshtastic/field_metadata.proto` comes from the same pinned
  `meshtastic/protobufs` revision. Meshtastic's config schemas use its
  `field_metadata` option annotations. It imports `google/protobuf/descriptor.proto`.
- `google/protobuf/descriptor.proto` comes from Protocol Buffers v3.25.5 and
  defines the standard option messages those extensions reference.

Keeping these files outside `src/main/proto` is intentional. Generating Java
for the option extensions emits references to
`com.google.protobuf.DescriptorProtos`, which the Android `protobuf-javalite`
runtime does not provide. The annotations are consumed by protoc while
compiling the other Meshtastic schemas; Meshdroid does not need runtime
reflection or generated classes for the options.
