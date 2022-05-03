# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Enables JASPI authentication for deployed web applications.

[tags]
security

[depend]
security
auth-config-factory

[lib]
lib/jetty-jaspi-${jetty.version}.jar
lib/jaspi/*.jar

[xml]
etc/jaspi/jaspi-authmoduleconfig.xml

[files]
basehome:etc/jaspi/jaspi-authmoduleconfig.xml|etc/jaspi/jaspi-authmoduleconfig.xml

