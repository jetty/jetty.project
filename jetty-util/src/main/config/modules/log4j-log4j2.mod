[description]
Provides a Log4j v1.2 to Log4j v2 logging bridge.  

[tags]
logging
log4j2
log4j
verbose

[depends]
log4j2-api
log4j2-impl

[provides]
log4j-api
log4j-impl

[files]
maven://org.apache.logging.log4j/log4j-1.2-api/${log4j2.version}|lib/log4j/log4j-1.2-api-${log4j2.version}.jar

[lib]
lib/log4j/log4j-1.2-api-${log4j2.version}.jar
