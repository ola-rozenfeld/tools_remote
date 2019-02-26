java_library(
    name = "logging",
    srcs = glob(["*.java"]),
    visibility = ["//:__subpackages__"],
    deps = [
        "//3rdparty/jvm/com/google/code/findbugs:jsr305",
        "//3rdparty/jvm/com/google/protobuf:protobuf_java",
        "//3rdparty/jvm/io/grpc:grpc_core",
        "//src/main/java/com/google/devtools/build/remote/client/util",
        "//src/main/proto:remote_execution_log_java_proto",
        "@googleapis//:google_bytestream_bytestream_java_grpc",
        "@googleapis//:google_bytestream_bytestream_java_proto",
        "@googleapis//:google_longrunning_operations_java_proto",
        "@googleapis//:google_rpc_status_java_proto",
        "@remoteapis//:remote_execution_java_grpc",
        "@remoteapis//build/bazel/remote/execution/v2:remote_execution_java_proto",
    ],
)
