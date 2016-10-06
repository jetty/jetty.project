[description]
Provides a Log4j v2 implementation. 
To receive jetty logs enable the jetty-slf4j, slf4j-log4j and log4j-log4j2 modules.

[tags]
logging
log4j2
log4j
verbose

[depends]
log4j2-api 
resources

[provides]
log4j2-impl

[files]
basehome:modules/log4j2/log4j2.xml|resources/log4j2.xml
maven://org.apache.logging.log4j/log4j-core/${log4j2.version}|lib/log4j/log4j-core-${log4j2.version}.jar

[lib]
lib/log4j/log4j-core-${log4j2.version}.jar

