# DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Enables logback access request log.

[tags]
requestlog

[depends]
logging-logback
server
resources

[xml]
etc/jetty-logback-access.xml

[files]
logs/
basehome:modules/logging/logback-access
maven://ch.qos.logback/logback-access/${logback.version}|lib/logback/logback-access-${logback.version}.jar

[lib]
lib/logback/logback-access-${logback.version}.jar

