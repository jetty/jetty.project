[description]
Configure jetty logging to use Java Util Logging (jul)
Uses SLF4j as a logging bridge.

[tags]
logging

[depends]
slf4j-jul

[provide]
logging

[exec]
-Dorg.eclipse.jetty.util.log.class=org.eclipse.jetty.util.log.Slf4jLog

[ini]
jetty.webapp.addServerClasses+=,file:${jetty.base}/lib/slf4j/
