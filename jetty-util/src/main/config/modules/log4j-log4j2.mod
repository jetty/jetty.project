[description]
Provides a Log4j v1.2 implementation that logs to the Log4j v2 API.  
Requires another module that provides and Log4j v2 implementation.
To receive jetty logs the jetty-slf4j and slf4j-log4j must also be enabled.

[tags]
logging
log4j2
log4j

[depends]
log4j2-api
log4j2-impl

[provides]
log4j-api
log4j-impl

[files]
maven://org.apache.logging.log4j/log4j-1.2-api/${log4j2.version}|lib/log4j/log4j-1.2-api-${log4j2.version}.jar

[lib]
lib/log4j/log4j-1.2-api-${log4j2.version}.jar
