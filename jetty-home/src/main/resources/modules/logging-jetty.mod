# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Base configuration for the jetty logging mechanism.
Provides a ${jetty.base}/resources/jetty-logging.properties.

[tags]
logging

[depends]
logging/slf4j
resources

[provides]
logging|default

[files]
basehome:modules/logging/jetty

[lib]
lib/logging/jetty-slf4j-impl-${jetty.version}.jar

[ini]
jetty.webapp.addServerClasses+=,org.eclipse.jetty.logging.
jetty.webapp.addServerClasses+=,${jetty.home.uri}/lib/logging/
