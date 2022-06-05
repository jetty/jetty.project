# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Enables a rewrite Rules container as a request customizer.
Enabled on the servers default HttpConfiguration instance

[provides]
rewrite

[depend]
server

[lib]
lib/jetty-rewrite-${jetty.version}.jar

[xml]
etc/jetty-rewrite-customizer.xml

[ini-template]
## Request attribute name used to store the original request path.
# jetty.rewrite.originalPathAttribute=jetty.rewrite.originalRequestPath
