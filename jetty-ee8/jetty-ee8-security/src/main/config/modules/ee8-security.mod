# DO NOT EDIT - See: https://eclipse.dev/jetty/documentation/current/startup-modules.html

[description]
Adds servlet standard security handling to the classpath.

[environment]
ee8

[depend]
server
security
ee8-servlet

[lib]
lib/jetty-ee8-security-${jetty.version}.jar
