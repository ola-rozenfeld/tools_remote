java_test(
    name = "ShellEscaperTest",
    srcs = ["ShellEscaperTest.java"],
    deps = [
        "//3rdparty/jvm/com/google/truth",
        "//src/main/java/com/google/devtools/build/remote/client/util",
    ],
)

java_test(
    name = "DockerUtilTest",
    srcs = ["DockerUtilTest.java"],
    deps = [
        "//3rdparty/jvm/com/google/truth",
        "//src/main/java/com/google/devtools/build/remote/client/util",
        "@com_google_guava_guava//jar",
        "@remoteapis//build/bazel/remote/execution/v2:remote_execution_java_proto",
    ],
)

java_test(
    name = "AsynchronousFileOutputStreamTest",
    srcs = ["AsynchronousFileOutputStreamTest.java"],
    deps = [
        "//3rdparty/jvm/com/google/truth",
        "//src/main/java/com/google/devtools/build/remote/client/util",
        "@com_google_guava_guava//jar",
        "@org_mockito_mockito_all//jar",
        "@remoteapis//build/bazel/remote/execution/v2:remote_execution_java_proto",
    ],
)
