java_library(
    name = "util",
    srcs = glob(["*.java"]),
    visibility = ["//:__subpackages__"],
    deps = [
        "//3rdparty/jvm/com/google/code/findbugs:jsr305",
        "//3rdparty/jvm/com/google/guava",
        "//3rdparty/jvm/com/google/protobuf:protobuf_java",
        "//3rdparty/jvm/io/grpc:grpc_context",
        "//3rdparty/jvm/io/grpc:grpc_core",
        "//3rdparty/jvm/io/grpc:grpc_protobuf",
        "//3rdparty/jvm/io/grpc:grpc_stub",
        "@remoteapis//build/bazel/remote/execution/v2:remote_execution_java_proto",
    ],
)
