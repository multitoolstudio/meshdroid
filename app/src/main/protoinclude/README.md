Proto files that are imported but never compiled.

The Gradle protobuf plugin compiles everything under `src/main/proto`. These two are on
protoc's include path instead, through `addIncludeDir` in `app/build.gradle.kts`, so their
definitions resolve for imports while no Java is generated from them.

  nanopb.proto                     from meshtastic/protobufs, commit ca2cb1a8
  google/protobuf/descriptor.proto from protocolbuffers/protobuf, tag v3.25.5

Both have to be here rather than in `src/main/proto`. nanopb.proto declares extensions to
descriptor.proto's option messages, and generating Java for those extensions would need
DescriptorProtos, which the javalite runtime does not ship. The Meshtastic protos only
carry nanopb options as annotations, which protoc has to parse but generates no code for,
so nothing is lost by leaving them uncompiled.

descriptor.proto is not bundled inside the protoc binary from Maven, and unlike
protobuf-java, the protobuf-javalite artifact does not carry the well-known types as
.proto files. Vendoring it is the least surprising fix.
