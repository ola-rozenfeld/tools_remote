cc_library(
    name = "proxy_client_lib",
    srcs = [
        "javac_remote_actions.cc",
        "link_command_inputs.cc",
        "common_utils.cc",
        "proxy_client.cc",
    ],
    hdrs = [
        "javac_remote_actions.h",
        "link_command_inputs.h",
        "common_utils.h",
        "proxy_client.h",
    ],
    deps = [
        "//external:gflags",
        "//external:grpc++",
        "//src/main/cc/ipc:goma_ipc_lib",
        "//src/main/proto:commands_cc_proto",
        "//src/main/proto:include_processor_cc_proto",
        "@com_google_absl//absl/strings",
    ],
)

cc_binary(
    name = "proxy_client",
    srcs = ["proxy_client_main.cc"],
    deps = [":proxy_client_lib"],
)

cc_binary(
    name = "fake_include_processor",
    srcs = ["fake_include_processor.cc"],
)
