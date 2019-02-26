# Code shamelessly stolen from Goma client.
package(default_visibility = ["//visibility:public"])

cc_library(
    name = "simple_timer_lib",
    srcs = ["simple_timer_linux.cc"],
    hdrs = ["simple_timer.h"],
    deps = ["@com_google_absl//absl/time"],
)

cc_library(
    name = "util_lib",
    srcs = ["util.cc"],
    hdrs = ["util.h"],
)

cc_library(
    name = "path_lib",
    srcs = ["path.cc"],
    hdrs = ["path.h"],
    deps = ["@com_google_absl//absl/strings"],
)

cc_library(
    name = "path_util_lib",
    srcs = ["path_util.cc"],
    hdrs = [
        "basictypes.h",
        "path_util.h",
    ],
    deps = ["@com_google_absl//absl/strings"],
)

cc_library(
    name = "path_resolver_lib",
    srcs = ["path_resolver.cc"],
    hdrs = ["path_resolver.h"],
    deps = [
        ":path_util_lib",
        "@com_google_absl//absl/container:inlined_vector",
        "@com_google_absl//absl/strings",
    ],
)

cc_library(
    name = "scoped_fd_lib",
    srcs = ["scoped_fd.cc"],
    hdrs = [
        "basictypes.h",
        "scoped_fd.h",
    ],
    deps = [
        ":path_resolver_lib",
        "@com_google_absl//absl/strings",
        "@com_google_absl//absl/time",
    ],
)

cc_library(
    name = "ioutil_lib",
    srcs = ["ioutil.cc"],
    hdrs = ["ioutil.h"],
    deps = [
        ":scoped_fd_lib",
        "@com_google_absl//absl/memory",
        "@com_google_absl//absl/strings",
        "@zlib_archive//:zlib",
    ],
)

cc_library(
    name = "http_util_lib",
    srcs = ["http_util.cc"],
    hdrs = ["http_util.h"],
    deps = [
        ":ioutil_lib",
        "@com_google_absl//absl/strings",
        "@zlib_archive//:zlib",
    ],
)

cc_library(
    name = "goma_ipc_peer_lib",
    srcs = ["goma_ipc_peer.cc"],
    hdrs = ["goma_ipc_peer.h"],
    deps = [":scoped_fd_lib"],
)

cc_library(
    name = "goma_ipc_addr_lib",
    srcs = ["goma_ipc_addr.cc"],
    hdrs = ["goma_ipc_addr.h"],
)

cc_library(
    name = "goma_ipc_lib",
    srcs = [
        "goma_ipc.cc",
    ],
    hdrs = [
        "compiler_proxy_info.h",
        "goma_ipc.h",
    ],
    deps = [
        ":goma_ipc_addr_lib",
        ":goma_ipc_peer_lib",
        ":http_util_lib",
        ":path_lib",
        ":path_resolver_lib",
        ":path_util_lib",
        ":scoped_fd_lib",
        ":simple_timer_lib",
        ":util_lib",
        "@com_google_absl//absl/strings",
        "@com_google_protobuf//:protobuf",
    ],
)
