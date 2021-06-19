# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Enables JASPI authentication for deployed web applications.

[depend]
security

[xml]
etc/jaspi/jetty-jaspi.xml

[lib]
lib/jetty-jaspi-${jetty.version}.jar
lib/jaspi/*.jar

[files]
basehome:modules/jaspi/jaspi-authmoduleconfig.xml|etc/jaspi-authmoduleconfig.xml

