[description]
Provides a Java Util Loggin binding to SLF4J logging.

[tags]
logging
slf4j
internal

[depends]
slf4j-api
slf4j-impl

[provides]
jul-api
jul-impl
slf4j+jul

[files]
maven://org.slf4j/jul-to-slf4j/${slf4j.version}|lib/slf4j/jul-to-slf4j-${slf4j.version}.jar
basehome:modules/jul-slf4j

[lib]
lib/slf4j/jul-to-slf4j-${slf4j.version}.jar

[exec]
-Djava.util.logging.config.file?=${jetty.base}/etc/java-util-logging.properties
