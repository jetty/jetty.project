[description]
Configure jetty logging to use Log4j Logging
SLF4J is used as the core logging mechanism.

[tags]
logging

[depends]
slf4j-log4j
log4j-impl

[provides]
logging

[exec]
-Dorg.eclipse.jetty.util.log.class?=org.eclipse.jetty.util.log.Slf4jLog
