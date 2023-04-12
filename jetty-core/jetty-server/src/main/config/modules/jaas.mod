# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Enables JAAS for deployed web applications.

[depend]
security

[before]
deploy

[xml]
etc/jetty-jaas.xml

[ini-template]
## The file location (relative to $jetty.base) for the
## JAAS "java.security.auth.login.config" system property
# jetty.jaas.login.conf=etc/login.conf
