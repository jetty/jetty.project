# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Enables the Jetty Quickstart module for rapid deployment of preconfigured web applications.

[environment]
ee9

[depend]
server
ee9-deploy

[lib]
lib/jetty-ee9-quickstart-${jetty.version}.jar

[xml]
etc/jetty-ee9-quickstart.xml

[ini-template]

# Modes are AUTO, GENERATE, QUICKSTART
# jetty.quickstart.mode=AUTO
# jetty.quickstart.origin=origin
# jetty.quickstart.xml=
