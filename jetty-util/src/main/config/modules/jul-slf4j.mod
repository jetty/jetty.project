[description]
Provides a Java Util Loggin binding to SLF4J logging.

[tags]
logging
slf4j
internal

[depend]
slf4j-api
slf4j-impl

[provide]
jul-impl

[files]
maven://org.slf4j/jul-to-slf4j/${slf4j.version}|lib/slf4j/jul-to-slf4j-${slf4j.version}.jar
basehome:modules/jul-slf4j/java-util-logging.properties|etc/java-util-logging.properties

[lib]
lib/slf4j/jul-to-slf4j-${slf4j.version}.jar

[exec]
-Djava.util.logging.config.file=etc/java-util-logging.properties

