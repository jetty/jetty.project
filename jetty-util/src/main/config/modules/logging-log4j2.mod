[description]
Configure jetty logging to use log4j version 2
Uses SLF4j as a logging bridge.

[tags]
logging

[depends]
slf4j-log4j2
log4j2-impl

[provide]
logging

[exec]
-Dorg.eclipse.jetty.util.log.class=org.eclipse.jetty.util.log.Slf4jLog

[ini]
jetty.webapp.addServerClasses,=file:${jetty.base}/lib/slf4j/,file:${jetty.base}/lib/log4j/
