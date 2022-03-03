# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Enables the Jetty Quickstart module for rapid deployment of preconfigured web applications.

[depend]
server
deploy

[lib]
lib/jetty-quickstart-${jetty.version}.jar

[xml]
etc/jetty-quickstart.xml

[files]
basehome:modules/jetty-quickstart.d/quickstart-webapp.xml|etc/quickstart-webapp.xml


[ini-template]

# Modes are AUTO, GENERATE, QUICKSTART
# jetty.quickstart.mode=AUTO
# jetty.quickstart.origin=origin
# jetty.quickstart.xml=
