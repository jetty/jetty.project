[description]
Provides a Java Commons Logging implementation that logs to the SLF4J API.  
Requires another module that provides and SLF4J implementation.

[tags]
logging
jcl
slf4j
verbose

[depends]
slf4j-api

[provides]
jcl-impl
jcl-api

[files]
maven://org.slf4j/jcl-over-slf4j/${slf4j.version}|lib/slf4j/jcl-over-slf4j-${slf4j.version}.jar

[lib]
lib/slf4j/jcl-over-slf4j-${slf4j.version}.jar
