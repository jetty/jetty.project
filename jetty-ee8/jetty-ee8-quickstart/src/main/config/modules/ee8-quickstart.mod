# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Enables the Jetty Quickstart module for rapid deployment of preconfigured web applications.

[environment]
ee8

[depend]
server
ee8-deploy

[lib]
lib/jetty-ee8-quickstart-${jetty.version}.jar

[xml]
etc/jetty-ee8-quickstart.xml

[ini-template]

# Modes are AUTO, GENERATE, QUICKSTART
# jetty.quickstart.mode=AUTO
# jetty.quickstart.origin=origin
# jetty.quickstart.xml=
