# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Capture java.util.logging events and bridge them to org.slf4j

[tags]
java-util-logging

[depends]
logging

[provides]
java-util-logging

[xml]
etc/logging-jul-capture.xml

[lib]
lib/logging/slf4j-api-${slf4j.version}.jar
lib/logging/jul-to-slf4j-${slf4j.version}.jar

[ini]
slf4j.version?=2.0.0-alpha1
