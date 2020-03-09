# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Capture jakarta-commons-logging events and bridge them to org.slf4j

[tags]
commons-logging

[depends]
logging

[provides]
commons-logging

[lib]
lib/logging/slf4j-api-${slf4j.version}.jar
lib/logging/jcl-over-slf4j-${slf4j.version}.jar

[ini]
slf4j.version?=2.0.0-alpha1
