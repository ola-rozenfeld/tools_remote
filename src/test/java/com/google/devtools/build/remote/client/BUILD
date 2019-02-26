java_test(
    name = "GrpcRemoteCacheTest",
    srcs = [
        "FakeImmutableCacheByteStreamImpl.java",
        "GrpcRemoteCacheTest.java",
    ],
    deps = [
        "//3rdparty/jvm/com/google/guava",
        "//3rdparty/jvm/com/google/http_client:google_http_client",
        "//3rdparty/jvm/com/google/http_client:google_http_client_jackson2",
        "//3rdparty/jvm/com/google/jimfs",
        "//3rdparty/jvm/com/google/protobuf:protobuf_java",
        "//3rdparty/jvm/com/google/truth",
        "//3rdparty/jvm/io/grpc:grpc_context",
        "//3rdparty/jvm/io/grpc:grpc_core",
        "//3rdparty/jvm/io/grpc:grpc_stub",
        "//src/main/java/com/google/devtools/build/remote/client",
        "//src/main/java/com/google/devtools/build/remote/client/util",
        "@googleapis//:google_bytestream_bytestream_java_grpc",
        "@googleapis//:google_bytestream_bytestream_java_proto",
        "@remoteapis//:remote_execution_java_grpc",
        "@remoteapis//build/bazel/remote/execution/v2:remote_execution_java_proto",
    ],
)

java_test(
    name = "ActionGroupingTest",
    srcs = ["ActionGroupingTest.java"],
    deps = [
        "//3rdparty/jvm/com/google/protobuf:protobuf_java_util",
        "//3rdparty/jvm/com/google/truth",
        "//src/main/java/com/google/devtools/build/remote/client",
        "//src/main/proto:remote_execution_log_java_proto",
        "@com_google_protobuf_protobuf_java//jar",
        "@googleapis//:google_longrunning_operations_java_proto",
        "@io_grpc_grpc_core//jar",
        "@remoteapis//build/bazel/remote/execution/v2:remote_execution_java_proto",
    ],
)

java_test(
    name = "RetrierTest",
    srcs = [
        "RemoteRetrierTest.java",
        "RetrierTest.java",
    ],
    deps = [
        "//3rdparty/jvm/com/google/guava",
        "//3rdparty/jvm/com/google/truth",
        "//src/main/java/com/google/devtools/build/remote/client",
        "@io_grpc_grpc_core//jar",
        "@org_mockito_mockito_all//jar",
    ],
)

java_test(
    name = "ChunkerTest",
    srcs = ["ChunkerTest.java"],
    deps = [
        "//3rdparty/jvm/com/google/guava",
        "//3rdparty/jvm/com/google/truth",
        "//src/main/java/com/google/devtools/build/remote/client",
        "//src/main/java/com/google/devtools/build/remote/client/util",
        "@com_google_protobuf_protobuf_java//jar",
        "@io_grpc_grpc_core//jar",
        "@org_mockito_mockito_all//jar",
        "@remoteapis//build/bazel/remote/execution/v2:remote_execution_java_proto",
    ],
)

java_test(
    name = "AbstractRemoteActionCacheTests",
    srcs = ["AbstractRemoteActionCacheTests.java"],
    deps = [
        "//3rdparty/jvm/com/google/guava",
        "//3rdparty/jvm/com/google/truth",
        "//src/main/java/com/google/devtools/build/remote/client",
        "//src/main/java/com/google/devtools/build/remote/client/util",
        "@com_google_protobuf_protobuf_java//jar",
        "@org_mockito_mockito_all//jar",
        "@remoteapis//build/bazel/remote/execution/v2:remote_execution_java_proto",
    ],
)

java_test(
    name = "ByteStreamUploaderTest",
    srcs = ["ByteStreamUploaderTest.java"],
    deps = [
        "//3rdparty/jvm/com/google/guava",
        "//3rdparty/jvm/com/google/http_client:google_http_client",
        "//3rdparty/jvm/com/google/http_client:google_http_client_jackson2",
        "//3rdparty/jvm/com/google/jimfs",
        "//3rdparty/jvm/com/google/protobuf:protobuf_java",
        "//3rdparty/jvm/com/google/protobuf:protobuf_java_util",
        "//3rdparty/jvm/com/google/truth",
        "//3rdparty/jvm/io/grpc:grpc_context",
        "//3rdparty/jvm/io/grpc:grpc_core",
        "//3rdparty/jvm/io/grpc:grpc_stub",
        "//src/main/java/com/google/devtools/build/remote/client",
        "//src/main/java/com/google/devtools/build/remote/client/util",
        "//src/main/proto:command_server_java_proto",
        "//src/main/proto:remote_execution_log_java_proto",
        "@com_google_protobuf_protobuf_java//jar",
        "@googleapis//:google_bytestream_bytestream_java_grpc",
        "@googleapis//:google_bytestream_bytestream_java_proto",
        "@googleapis//:google_longrunning_operations_java_proto",
        "@googleapis//:google_rpc_status_java_proto",
        "@io_grpc_grpc_core//jar",
        "@org_mockito_mockito_all//jar",
        "@remoteapis//:remote_execution_java_grpc",
        "@remoteapis//build/bazel/remote/execution/v2:remote_execution_java_proto",
    ],
)

java_test(
    name = "TreeNodeRepositoryTest",
    srcs = ["TreeNodeRepositoryTest.java"],
    deps = [
        "//3rdparty/jvm/com/google/guava",
        "//3rdparty/jvm/com/google/truth",
        "//src/main/java/com/google/devtools/build/remote/client",
        "//src/main/java/com/google/devtools/build/remote/client/util",
        "@com_google_protobuf_protobuf_java//jar",
        "@org_mockito_mockito_all//jar",
        "@remoteapis//build/bazel/remote/execution/v2:remote_execution_java_proto",
    ],
)
