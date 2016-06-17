[description]
Provides SLF4J bridge to apache java commons logging

[depend]
slf4j-api
jcl-api

[provide]
slf4j-impl

[files]
maven://org.slf4j/slf4j-jcl/1.7.21|lib/slf4j/slf4j-jcl-1.7.21.jar

[lib]
lib/slf4j/slf4j-jcl-1.7.21.jar

[exec]
-Dorg.eclipse.jetty.util.log.class=org.eclipse.jetty.util.log.Slf4jLog
