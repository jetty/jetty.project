#
# Jetty Rewrite Customizer module
#
# Apply rewrite rules as a request customizer applied to all
# connectors sharing a HttpConfiguration
#
[depend]
server

[lib]
lib/jetty-rewrite-${jetty.version}.jar

[xml]
etc/jetty-rewrite-customizer.xml

[ini-template]
## Whether to rewrite the request URI
# jetty.rewrite.rewriteRequestURI=true

## Whether to rewrite the path info
# jetty.rewrite.rewritePathInfo=true

## Request attribute key under with the original path is stored
# jetty.rewrite.originalPathAttribute=requestedPath
