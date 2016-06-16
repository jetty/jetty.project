[description]
Provides SLF4J simple logging

[depend]
slf4j-api

[provide]
slf4j-impl

[files]
maven://org.slf4j/slf4j-simple/1.7.21|lib/slf4j/slf4j-simple-1.7.21.jar

[lib]
lib/slf4j/slf4j-simple-1.7.21.jar

[exec]
-Dorg.eclipse.jetty.util.log.class=org.eclipse.jetty.util.log.Slf4jLog
