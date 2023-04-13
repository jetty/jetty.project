# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Enables JAAS for deployed web applications.

[environment]
ee10

[depend]
server
jaas

[lib]
lib/jetty-ee10-jaas-${jetty.version}.jar

[xml]
etc/jetty-ee10-jaas.xml

[ini-template]
## The file location (relative to $jetty.base) for the
## JAAS "java.security.auth.login.config" system property
# jetty.jaas.login.conf=etc/login.conf
