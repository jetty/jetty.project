[description]
Provides a SLF4J implementation that logs to the Log4j v2 API.  
Requires another module that provides a Log4j2 implementation.
To receive jetty logs enable the jetty-slf4j2 module.

[tags]
logging
log4j2
log4j
slf4j

[depend]
slf4j-api
log4j2-api

[provide]
slf4j-impl

[files]
maven://org.apache.logging.log4j/log4j-slf4j-impl/${log4j2.version}|lib/log4j/log4j-slf4j-impl-${log4j2.version}.jar

[lib]
lib/log4j/log4j-slf4j-impl-${log4j2.version}.jar
