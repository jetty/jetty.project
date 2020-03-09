# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Capture Apache log4j events and bridge them to org.slf4j

[tags]
log4j

[depends]
logging

[provides]
log4j

[lib]
lib/logging/slf4j-api-${slf4j.version}.jar
lib/logging/log4j-to-slf4j-${slf4j.version}.jar

[ini]
slf4j.version?=2.0.0-alpha1
