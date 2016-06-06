#
# Jetty Rewrite module
#
# Install rewrite rules as a handler applied to all requests on a server
#
[depend]
server

[lib]
lib/jetty-rewrite-${jetty.version}.jar

[xml]
etc/jetty-rewrite.xml

[ini-template]
## Whether to rewrite the request URI
# jetty.rewrite.rewriteRequestURI=true

## Whether to rewrite the path info
# jetty.rewrite.rewritePathInfo=false

## Request attribute key under with the original path is stored
# jetty.rewrite.originalPathAttribute=requestedPath
