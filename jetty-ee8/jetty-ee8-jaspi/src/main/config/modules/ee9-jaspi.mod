# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Enables JASPI authentication for deployed web applications.

[tags]
security

[depend]
ee8-security
ee8-auth-config-factory

[lib]
lib/jetty-ee8-jaspi-${jetty.version}.jar
lib/jaspi/*.jar

[xml]
etc/jaspi/jetty-ee8-jaspi-authmoduleconfig.xml

[files]
basehome:etc/jaspi/jetty-ee8-jaspi-authmoduleconfig.xml|etc/jaspi/jetty-ee8-jaspi-authmoduleconfig.xml

