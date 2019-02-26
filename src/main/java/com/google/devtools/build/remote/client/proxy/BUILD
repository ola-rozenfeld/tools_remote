licenses(["notice"])  # Apache 2.0

java_library(
    name = "proxy",
    srcs = glob(["*.java"]),
    visibility = ["//:__subpackages__"],
    deps = [
        "//3rdparty/jvm/com/beust:jcommander",
        "//3rdparty/jvm/com/google/auth:google_auth_library_credentials",
        "//3rdparty/jvm/com/google/auth:google_auth_library_oauth2_http",
        "//3rdparty/jvm/com/google/code/findbugs:jsr305",
        "//3rdparty/jvm/com/google/guava",
        "//3rdparty/jvm/com/google/protobuf:protobuf_java",
        "//3rdparty/jvm/com/google/protobuf:protobuf_java_util",
        "//3rdparty/jvm/io/grpc:grpc_auth",
        "//3rdparty/jvm/io/grpc:grpc_context",
        "//3rdparty/jvm/io/grpc:grpc_core",
        "//3rdparty/jvm/io/grpc:grpc_netty",
        "//3rdparty/jvm/io/grpc:grpc_protobuf",
        "//3rdparty/jvm/io/grpc:grpc_stub",
        "//3rdparty/jvm/io/netty:netty_all",
        "//src/main/java/com/google/devtools/build/remote/client",
        "//src/main/java/com/google/devtools/build/remote/client/util",
        "//src/main/proto:command_server_java_grpc",
        "//src/main/proto:command_server_java_proto",
        "@remoteapis//build/bazel/remote/execution/v2:remote_execution_java_proto",
    ],
)
