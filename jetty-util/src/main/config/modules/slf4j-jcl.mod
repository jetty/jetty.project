[description]
Provides a SLF4J binding to Java Commons Logging (JCL) logging.

[tags]
logging
jcl
slf4j
internal

[depends]
slf4j-api
jcl-impl

[provides]
slf4j-impl
slf4j+jcl

[files]
maven://org.slf4j/slf4j-jcl/${slf4j.version}|lib/slf4j/slf4j-jcl-${slf4j.version}.jar

[lib]
lib/slf4j/slf4j-jcl-${slf4j.version}.jar
