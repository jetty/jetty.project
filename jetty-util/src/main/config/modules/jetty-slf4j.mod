[description]
Configure Jetty logging to use SLF4J

[depend]
slf4j-api
slf4j-impl

[provide]
logging

[exec]
-Dorg.eclipse.jetty.util.log.class=org.eclipse.jetty.util.log.Slf4jLog
