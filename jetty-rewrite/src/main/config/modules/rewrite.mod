DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Enables the jetty-rewrite handler.  Specific rewrite
rules must be added to either to etc/jetty-rewrite.xml or a custom xml/module

[provides]
rewrite

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
