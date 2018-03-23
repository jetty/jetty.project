DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Provides a SLF4J binding to Log4j v2 logging.

[tags]
logging
log4j2
log4j
slf4j
internal

[depends]
slf4j-api
log4j2-api
log4j2-impl

[provides]
slf4j-impl

[files]
maven://org.apache.logging.log4j/log4j-slf4j-impl/${log4j2.version}|lib/log4j2/log4j-slf4j-impl-${log4j2.version}.jar

[lib]
lib/log4j2/log4j-slf4j-impl-${log4j2.version}.jar
