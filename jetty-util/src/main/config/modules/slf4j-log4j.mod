[description]
Provides a SLF4J implementation that logs to the Log4j v1.2 API.  
Requires another module that provides a Log4j implementation.
To receive jetty logs enable the jetty-slf4j module.

[tags]
logging
log4j
slf4j

[depend]
slf4j-api
log4j-api

[provide]
slf4j-impl

[files]
maven://org.slf4j/slf4j-log4j12/${slf4j.version}|lib/slf4j/slf4j-log4j12-${slf4j.version}.jar

[lib]
lib/slf4j/slf4j-log4j12-${slf4j.version}.jar

