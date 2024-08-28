# DO NOT EDIT THIS FILE - See: https://jetty.org/docs/

[description]
Enables standard Servlet handling.

[environment]
ee8

[depend]
server
sessions

[lib]
lib/jetty-servlet-api-@jetty.servlet.api.version@.jar
lib/jetty-ee8-nested-${jetty.version}.jar
lib/jetty-ee8-servlet-${jetty.version}.jar
