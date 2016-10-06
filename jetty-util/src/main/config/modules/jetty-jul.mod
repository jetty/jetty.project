[description]
Provides a Jetty Logging implementation that logs to the Java Util Logging API.  
Requires another module that provides a Java Util Logging implementation.

[tags]
logging

[provide]
logging

[exec]
-Dorg.eclipse.jetty.util.log.class=org.eclipse.jetty.util.log.JavaUtilLog
