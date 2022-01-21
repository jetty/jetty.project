# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Enables the jetty-rewrite handler.  
Specific rewrite rules must be added to either to etc/jetty-rewrite.xml or a custom xml/module.

[tags]
server

[provides]
rewrite|default

[depend]
server

[lib]
lib/jetty-rewrite-${jetty.version}.jar

[files]
basehome:modules/rewrite/rewrite-rules.xml|etc/rewrite-rules.xml

[xml]
etc/jetty-rewrite.xml
etc/rewrite-rules.xml

[ini-template]
## Whether to rewrite the request URI
# jetty.rewrite.rewriteRequestURI=true

## Whether to rewrite the path info
# jetty.rewrite.rewritePathInfo=false

## Request attribute key under with the original path is stored
# jetty.rewrite.originalPathAttribute=requestedPath
