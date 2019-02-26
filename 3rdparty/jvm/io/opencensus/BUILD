java_library(
    name = "opencensus_api",
    exports = [
        "//external:jar/io/opencensus/opencensus_api"
    ],
    runtime_deps = [
        "//3rdparty/jvm/com/google/errorprone:error_prone_annotations",
        "//3rdparty/jvm/com/google/guava:guava"
    ],
    visibility = [
        "//visibility:public"
    ]
)



java_library(
    name = "opencensus_contrib_grpc_metrics",
    exports = [
        "//external:jar/io/opencensus/opencensus_contrib_grpc_metrics"
    ],
    runtime_deps = [
        "//3rdparty/jvm/com/google/errorprone:error_prone_annotations",
        ":opencensus_api"
    ],
    visibility = [
        "//visibility:public"
    ]
)


