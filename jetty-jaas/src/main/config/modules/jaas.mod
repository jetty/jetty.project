DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Enable JAAS for deployed webapplications.

[depend]
server

[lib]
lib/jetty-jaas-${jetty.version}.jar

[xml]
etc/jetty-jaas.xml

[ini-template]
## The file location (relative to $jetty.base) for the
## JAAS "java.security.auth.login.config" system property
# jetty.jaas.login.conf=etc/login.conf
