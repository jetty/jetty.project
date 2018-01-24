java_library(
    name = "jetty",
    srcs = glob(["jetty-alpn/jetty-alpn-server/src/main/**/*.java"]),
    visibility = ["//visibility:public"],
    deps = [
        "@jetty_alpn_api//jar",
        "@jetty_server//jar",
        "@jetty_http//jar",
        "@jetty_util//jar",
        "@jetty_io//jar",
        "@jetty_jmx//jar",
        "@javax_servlet_api//jar",
    ],
)