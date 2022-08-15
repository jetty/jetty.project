# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Enables standard Servlet handling.

[environment]
ee9

[depend]
server
sessions

# FIXME should servlet api version be interpolated
[lib]
lib/jetty-jakarta-servlet-api-@jetty.servlet.api.version@.jar
lib/jetty-ee9-nested-${jetty.version}.jar
lib/jetty-ee9-servlet-${jetty.version}.jar
