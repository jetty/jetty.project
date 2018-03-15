DO NOT EDIT - See: https://www.eclipse.org/jetty/documentation/current/startup-modules.html

[description]
Provides a SLF4J binding to Java Util Logging (JUL) logging.

[tags]
logging
slf4j
internal

[depends]
slf4j-api

[provides]
slf4j-impl
slf4j+jul

[files]
maven://org.slf4j/slf4j-jdk14/${slf4j.version}|lib/slf4j/slf4j-jdk14-${slf4j.version}.jar

[lib]
lib/slf4j/slf4j-jdk14-${slf4j.version}.jar
