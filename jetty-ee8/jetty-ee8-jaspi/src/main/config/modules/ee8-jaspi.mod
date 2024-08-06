# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Enables JASPI authentication for deployed web applications.

[tags]
security

[depend]
ee8-security
ee8-auth-config-factory

[ini]
ee8.jakarta.authentication.api.version?=@jakarta.authentication.api.version@

[lib]
lib/jetty-ee8-jaspi-${jetty.version}.jar
lib/ee8-jaspi/jakarta.authentication-api-${ee8.jakarta.authentication.api.version}.jar

[xml]
etc/jaspi/jetty-ee8-jaspi-authmoduleconfig.xml

[files]
basehome:etc/jaspi/jetty-ee8-jaspi-authmoduleconfig.xml|etc/jaspi/jetty-ee8-jaspi-authmoduleconfig.xml

