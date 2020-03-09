# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Configure jetty logging mechanism.
Provides a ${jetty.base}/resources/jetty-logging.properties.

[tags]
logging

[depends]
resources

[provides]
logging|default

[files]
basehome:modules/logging/jetty

[lib]
lib/logging/slf4j-api-${slf4j.version}.jar
lib/logging/jetty-slf4j-impl-${jetty.version}.jar

[ini]
slf4j.version?=2.0.0-alpha1
jetty.webapp.addSystemClasses+=,org.slf4j.,org.eclipse.jetty.logging.
