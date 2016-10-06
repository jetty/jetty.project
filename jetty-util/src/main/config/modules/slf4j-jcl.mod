[description]
Provides a SLF4J implementation that logs to the Java Commons Logging API.  
Requires another module that provides an JCL implementation.
To receive jetty logs enable the jetty-slf4j module.

[tags]
logging
jcl
slf4j
verbose

[depend]
slf4j-api
jcl-api

[provide]
slf4j-impl

[files]
maven://org.slf4j/slf4j-jcl/${slf4j.version}|lib/slf4j/slf4j-jcl-${slf4j.version}.jar

[lib]
lib/slf4j/slf4j-jcl-${slf4j.version}.jar
