# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Enables the Jetty Quickstart module for rapid deployment of preconfigured web applications.

[environment]
ee10

[depend]
server
deploy

[lib]
lib/jetty-ee10-quickstart-${jetty.version}.jar

[xml]
etc/jetty-ee10-quickstart.xml

[ini-template]

# Modes are AUTO, GENERATE, QUICKSTART
# jetty.quickstart.mode=AUTO
# jetty.quickstart.origin=origin
# jetty.quickstart.xml=
