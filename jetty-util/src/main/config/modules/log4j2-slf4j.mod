[description]
Provides a Log4j v2 implementation that logs to the SLF4J API.  
Requires another module that provides and SLF4J implementation.
To receive jetty logs enable the jetty-slf4j module.

[depends]
log4j2-api
slf4j-api

[provides]
log4j2-impl

[files]
maven://org.apache.logging.log4j/log4j-to-slf4j/${log4j2.version}|lib/log4j/log4j-to-slf4j-${log4j2.version}.jar

[lib]
lib/log4j/log4j-slf4j-to-${log4j2.version}.jar

