# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Enables the jetty-rewrite handler.  
Specific rewrite rules must be added to etc/jetty-rewrite-rules.xml.

[tags]
server

[provides]
rewrite|default

[depend]
server

[lib]
lib/jetty-rewrite-${jetty.version}.jar

[files]
basehome:modules/rewrite/jetty-rewrite-rules.xml|etc/jetty-rewrite-rules.xml

[xml]
etc/jetty-rewrite.xml
etc/jetty-rewrite-rules.xml

[ini-template]
## Request attribute name used to store the original request path.
# jetty.rewrite.originalPathAttribute=jetty.rewrite.originalRequestPath
