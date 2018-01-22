java_library(
    name = "jetty",
    srcs = glob(["jetty-server/src/main/**/*.java"]),
    visibility = ["//visibility:public"],
    deps = [
        "@jetty_alpn_server//jar",
        "@jetty_server//jar",
        "@jetty_http//jar",
        "@jetty_util//jar",
        "@jetty_io//jar",
        "@javax_sevlet_api//jar",
    ],
)