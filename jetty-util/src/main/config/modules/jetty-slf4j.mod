[description]
Provides a Jetty Logging implementation that logs to the SLF4J API.  
Requires another module that provides and SLF4J implementation.

[tags]
logging
slf4j

[depend]
slf4j-api
slf4j-impl

[provide]
logging

[exec]
-Dorg.eclipse.jetty.util.log.class=org.eclipse.jetty.util.log.Slf4jLog
