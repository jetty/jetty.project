# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Adds servlet standard security handling to the classpath.

[environment]
ee10

[depend]
server
security
ee10-servlet

[lib]
lib/jetty-ee10-security-${jetty.version}.jar
