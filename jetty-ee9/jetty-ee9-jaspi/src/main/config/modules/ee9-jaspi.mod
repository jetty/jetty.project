# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Enables JASPI authentication for deployed web applications.

[tags]
security

[depend]
ee9-security
ee9-auth-config-factory

[lib]
lib/jetty-ee9-jaspi-${jetty.version}.jar
lib/jaspi/*.jar

[xml]
etc/jaspi/jetty-ee9-jaspi-authmoduleconfig.xml

[files]
basehome:etc/jaspi/jetty-ee9-jaspi-authmoduleconfig.xml|etc/jaspi/jetty-ee9-jaspi-authmoduleconfig.xml

