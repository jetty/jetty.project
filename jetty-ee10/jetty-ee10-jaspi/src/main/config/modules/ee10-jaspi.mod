# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Enables JASPI authentication for deployed web applications.

[environment]
ee10

[tags]
security

[depend]
ee10-security
auth-config-factory

[lib]
lib/jetty-ee10-jaspi-${jetty.version}.jar
lib/ee10-jaspi/*.jar

[xml]
etc/jaspi/jetty-ee10-jaspi-authmoduleconfig.xml

[files]
basehome:etc/jaspi/jetty-ee10-jaspi-authmoduleconfig.xml|etc/jaspi/jetty-ee10-jaspi-authmoduleconfig.xml

