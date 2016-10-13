[description]
Configure jetty logging to use slf4j.
Any slf4j-impl implementation is used

[tags]
logging

[depends]
slf4j-impl

[provide]
logging

[exec]
-Dorg.eclipse.jetty.util.log.class=org.eclipse.jetty.util.log.Slf4jLog

[ini]
jetty.webapp.addServerClasses,=file:${jetty.base}/lib/slf4j/
