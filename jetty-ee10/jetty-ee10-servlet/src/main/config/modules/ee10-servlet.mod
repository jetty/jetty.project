# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Enables standard Servlet handling.

[environment]
ee10

[depend]
server
sessions

[lib]
lib/jakarta.servlet-api-@jakarta.servlet.api.version@.jar
lib/jetty-ee10-servlet-${jetty.version}.jar
