package(default_visibility = ["//visibility:public"])

load("@com_github_grpc_grpc//bazel:cc_grpc_library.bzl", "cc_grpc_library")
load("@//third_party/grpc_java:java_grpc_library.bzl", "java_grpc_library")

proto_library(
    name = "remote_execution_log_proto",
    srcs = ["remote_execution_log.proto"],
    deps = [
        "@com_google_protobuf//:timestamp_proto",
        "@googleapis//:google_bytestream_bytestream_proto",
        "@googleapis//:google_devtools_remoteexecution_v1test_remote_execution_proto",
        "@googleapis//:google_longrunning_operations_proto",
        "@googleapis//:google_rpc_status_proto",
        "@googleapis//:google_watch_v1_proto",
        "@remoteapis//build/bazel/remote/execution/v2:remote_execution_proto",
    ],
)

java_proto_library(
    name = "remote_execution_log_java_proto",
    deps = [":remote_execution_log_proto"],
)

proto_library(
    name = "commands_proto",
    srcs = ["commands.proto"],
    deps = [
        "@remoteapis//build/bazel/remote/execution/v2:remote_execution_proto",
    ],
)

proto_library(
    name = "stats_proto",
    srcs = ["stats.proto"],
    deps = [
        ":commands_proto",
        "@com_google_protobuf//:timestamp_proto",
        "@remoteapis//build/bazel/remote/execution/v2:remote_execution_proto",
    ],
)

cc_grpc_library(
    name = "commands_cc_proto",
    srcs = ["commands.proto"],
    proto_only = False,
    use_external = False,
    well_known_protos = True,
    deps = ["@remoteapis//build/bazel/remote/execution/v2:remote_execution_cc_proto"],
)

cc_grpc_library(
    name = "stats_cc_proto",
    srcs = ["stats.proto"],
    proto_only = False,
    use_external = False,
    well_known_protos = True,
    deps = [
        ":commands_cc_proto",
        "@remoteapis//build/bazel/remote/execution/v2:remote_execution_cc_proto",
    ],
)

cc_grpc_library(
    name = "include_processor_cc_proto",
    srcs = ["include_processor.proto"],
    proto_only = False,
    use_external = False,
    well_known_protos = True,
    deps = [],
)

java_proto_library(
    name = "commands_java_proto",
    deps = [":commands_proto"],
)

java_proto_library(
    name = "stats_java_proto",
    deps = [":stats_proto"],
)

java_grpc_library(
    name = "commands_java_grpc",
    srcs = [":commands_proto"],
    deps = [":commands_java_proto"],
)

java_grpc_library(
    name = "stats_java_grpc",
    srcs = [":stats_proto"],
    deps = [":stats_java_proto"],
)
