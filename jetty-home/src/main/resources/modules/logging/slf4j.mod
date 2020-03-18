# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Configure logging to use slf4j with no impl
(If you don't select an impl, then NOP will be used by slf4j)

[tags]
logging

[provides]
slf4j

[lib]
lib/logging/slf4j-api-${slf4j.version}.jar

[ini]
slf4j.version?=2.0.0-alpha1
jetty.webapp.addServerClasses+=,org.slf4j.
