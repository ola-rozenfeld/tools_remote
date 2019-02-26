workspace(name = "remote_client")

load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")

# Needed for protobuf.
http_archive(
    name = "bazel_skylib",
    # Commit f83cb8dd6f5658bc574ccd873e25197055265d1c of 2018-11-26
    sha256 = "ba5d15ca230efca96320085d8e4d58da826d1f81b444ef8afccd8b23e0799b52",
    strip_prefix = "bazel-skylib-f83cb8dd6f5658bc574ccd873e25197055265d1c",
    urls = [
        "https://github.com/bazelbuild/bazel-skylib/archive/f83cb8dd6f5658bc574ccd873e25197055265d1c.tar.gz",
    ],
)

# Needed for "well-known protos" and protoc.
http_archive(
    name = "com_google_protobuf",
    sha256 = "3e933375ecc58d01e52705479b82f155aea2d02cc55d833f8773213e74f88363",
    strip_prefix = "protobuf-3.7.0",
    urls = ["https://github.com/protocolbuffers/protobuf/releases/download/v3.7.0/protobuf-all-3.7.0.tar.gz"],
)

# Needed for C++ gRPC.
http_archive(
    name = "com_github_grpc_grpc",
    strip_prefix = "grpc-1.17.2",
    urls = [
        "https://github.com/grpc/grpc/archive/v1.17.2.tar.gz",
        "https://mirror.bazel.build/github.com/grpc/grpc/archive/v1.17.2.tar.gz",
    ],
    sha256 = "34ed95b727e7c6fcbf85e5eb422e962788e21707b712fdb4caf931553c2c6dbc",
)

# Pull in all gRPC dependencies.
load("@com_github_grpc_grpc//bazel:grpc_deps.bzl", "grpc_deps")
grpc_deps()

bind(
    name = "grpc_cpp_plugin",
    actual = "@com_github_grpc_grpc//:grpc_cpp_plugin",
)

bind(
    name = "grpc++",
    actual = "@com_github_grpc_grpc//:grpc++",
)

http_archive(
    name = "com_google_absl",
    strip_prefix = "abseil-cpp-256be563447a315f2a7993ec669460ba475fa86a",
    url = "https://github.com/abseil/abseil-cpp/archive/256be563447a315f2a7993ec669460ba475fa86a.zip",
#    sha256 = "84b4277a9b56f9a192952beca535313497826c6ff2e38b2cac7351a3ed2ae780",
)

# Needed for @grpc_java//compiler:grpc_java_plugin.
http_archive(
    name = "grpc_java",
    sha256 = "000a6f8579f1b93e5d1b085c29d89dbc1ea8b5a0c16d7427f42715f0d7f0b247",
    strip_prefix = "grpc-java-d792a72ea15156254e3b3735668e9c4539837fd3",
    urls = ["https://github.com/grpc/grpc-java/archive/d792a72ea15156254e3b3735668e9c4539837fd3.zip"],
)

http_archive(
    name = "googleapis",
    build_file = "@//:BUILD.googleapis",
    sha256 = "7b6ea252f0b8fb5cd722f45feb83e115b689909bbb6a393a873b6cbad4ceae1d",
    strip_prefix = "googleapis-143084a2624b6591ee1f9d23e7f5241856642f4d",
    url = "https://github.com/googleapis/googleapis/archive/143084a2624b6591ee1f9d23e7f5241856642f4d.zip",
)

http_archive(
    name = "remoteapis",
    build_file = "@//:BUILD.remoteapis",
#    sha256 = "6f22ba09356f8dbecb87ba03cacf147939f77fef1c9cfaffb3826691f3686e9b",
    strip_prefix = "remote-apis-3e5d6da487df88c62be9aed0a8130f588f26b712",
    url = "https://github.com/ola-rozenfeld/remote-apis/archive/3e5d6da487df88c62be9aed0a8130f588f26b712.zip",
)

#http_archive(
#    name = "remoteapis",
#    build_file = "@//:BUILD.remoteapis",
#    sha256 = "6f22ba09356f8dbecb87ba03cacf147939f77fef1c9cfaffb3826691f3686e9b",
#    strip_prefix = "remote-apis-cfe8e540cbb424e3ebc649ddcbc91190f70e23a6",
#    url = "https://github.com/bazelbuild/remote-apis/archive/cfe8e540cbb424e3ebc649ddcbc91190f70e23a6.zip",
#)

# Bazel toolchains
http_archive(
    name = "bazel_toolchains",
    sha256 = "7e85a14821536bc24e04610d309002056f278113c6cc82f1059a609361812431",
    strip_prefix = "bazel-toolchains-bc0091adceaf4642192a8dcfc46e3ae3e4560ea7",
    urls = [
        "https://mirror.bazel.build/github.com/bazelbuild/bazel-toolchains/archive/bc0091adceaf4642192a8dcfc46e3ae3e4560ea7.tar.gz",
        "https://github.com/bazelbuild/bazel-toolchains/archive/bc0091adceaf4642192a8dcfc46e3ae3e4560ea7.tar.gz",
    ],
)

# Needed for C++ proxy_client in-socket connection.
http_archive(
    name = "zlib_archive",
    urls = [
        "https://mirror.bazel.build/zlib.net/zlib-1.2.11.tar.gz",
    ],
    sha256 = "c3e5e9fdd5004dcb542feda5ee4f0ff0744628baf8ed2dd5d66f8ca1197cb1a1",
    strip_prefix = "zlib-1.2.11",
    build_file = "@//:BUILD.zlib",
)

load("//3rdparty:workspace.bzl", "maven_dependencies", "jar_artifact_callback")

maven_dependencies(jar_artifact_callback)
