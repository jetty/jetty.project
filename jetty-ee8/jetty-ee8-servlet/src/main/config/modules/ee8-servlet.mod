# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Enables standard Servlet handling.

[environment]
ee8

[depend]
server
sessions

# FIXME should servlet api version be interpolated
[lib]
lib/jetty-jakarta-servlet-api-4.0.4.jar
lib/jetty-ee8-nested-${jetty.version}.jar
lib/jetty-ee8-servlet-${jetty.version}.jar
