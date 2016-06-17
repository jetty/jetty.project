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
maven://org.apache.logging.log4j/log4j-to-slf4j/2.6.1|lib/log4j/log4j-to-slf4j-2.6.1.jar

[lib]
lib/log4j/log4j-slf4j-to-2.6.1.jar

