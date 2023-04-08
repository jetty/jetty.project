# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Adds servlet standard security handling to the classpath.

[environment]
ee9

[depend]
server
ee9-servlet

[lib]
lib/jetty-security-${jetty.version}.jar
lib/jetty-ee9-security-${jetty.version}.jar
