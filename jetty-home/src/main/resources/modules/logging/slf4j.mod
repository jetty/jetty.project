# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Configures logging to use SLF4J.
A specific implementation of SLF4J is not enabled. 
If one is not selected then NOP implementation will be used.

[tags]
logging

[provides]
slf4j

[lib]
lib/logging/slf4j-api-${slf4j.version}.jar

[ini]
slf4j.version?=@slf4j.version@
jetty.webapp.addServerClasses+=,org.slf4j.
