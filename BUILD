java_binary(
    name = "remote_client",
    main_class = "com.google.devtools.build.remote.client.RemoteClient",
    visibility = ["//visibility:public"],
    runtime_deps = [
        "//src/main/java/com/google/devtools/build/remote/client",
    ],
)

java_binary(
    name = "remote_client_proxy",
    main_class = "com.google.devtools.build.remote.client.proxy.RemoteProxyServer",
    visibility = ["//visibility:public"],
    runtime_deps = [
        "//src/main/java/com/google/devtools/build/remote/client/proxy",
    ],
)
