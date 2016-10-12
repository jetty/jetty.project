[description]
Configure jetty logging to use Log4j Logging
Uses SLF4j as a logging bridge.

[tags]
logging

[depends]
slf4j-log4j
log4j-impl

[provide]
logging

[exec]
-Dorg.eclipse.jetty.util.log.class=org.eclipse.jetty.util.log.Slf4jLog

[ini-template]
## Hide logging classes from deployed webapps
jetty.webapp.addServerClasses,=file:${jetty.base}/lib/slf4j/,file:${jetty.base}/lib/log4j/
